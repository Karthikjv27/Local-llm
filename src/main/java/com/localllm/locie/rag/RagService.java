package com.localllm.locie.rag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Indexes a local folder and retrieves the passages most relevant to a question.
 *
 * <p>Indexing is transactional per call: if an embedding request fails halfway through a folder,
 * nothing is written, so the index never contains half a file. Retrieval ranks every chunk with an
 * exact cosine score and returns at most {@code topK} passages, capped per document so one long
 * file cannot fill the prompt on its own.
 */
@Slf4j
@Service
public class RagService {

    /** Text-ish formats only; a folder is expected to contain code and notes, not model weights. */
    private static final Set<String> EXTENSIONS = Set.of(
            "md", "markdown", "txt", "rst", "adoc", "java", "kt", "py", "js", "ts", "tsx", "jsx",
            "json", "yml", "yaml", "sql", "html", "css", "properties", "toml", "sh", "gradle");

    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".idea", ".gradle", ".venv", "venv", "node_modules", "__pycache__",
            "target", "dist", "build", "out", "coverage");

    /** Ollama handles a batch well; very large batches just make one slow request. */
    private static final int EMBED_BATCH = 16;
    private static final int MAX_CHUNKS_PER_DOCUMENT = 2;
    private static final int PROMPT_CHARS = 1_200;

    private final RagDocumentRepository documents;
    private final RagChunkRepository chunks;
    private final Embedder embedder;
    private final int chunkSize;
    private final int overlap;
    private final int topK;
    private final double minScore;
    private final long maxFileBytes;
    private final int maxFiles;

    public RagService(RagDocumentRepository documents, RagChunkRepository chunks, Embedder embedder,
                      @Value("${locie.rag.chunk-size:1200}") int chunkSize,
                      @Value("${locie.rag.chunk-overlap:200}") int overlap,
                      @Value("${locie.rag.top-k:5}") int topK,
                      @Value("${locie.rag.min-score:0.30}") double minScore,
                      @Value("${locie.rag.max-file-bytes:1048576}") long maxFileBytes,
                      @Value("${locie.rag.max-files:2000}") int maxFiles) {
        this.documents = documents;
        this.chunks = chunks;
        this.embedder = embedder;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.topK = topK;
        this.minScore = minScore;
        this.maxFileBytes = maxFileBytes;
        this.maxFiles = maxFiles;
    }

    /**
     * Indexes every supported file under {@code folder}. A file whose content hash is unchanged is
     * skipped without calling the embedding model again, which makes re-indexing a folder cheap.
     */
    @Transactional
    public RagIndexResult index(String folder) {
        if (folder == null || folder.isBlank()) {
            throw new IllegalArgumentException("A folder path is required");
        }
        Path root;
        try {
            root = Path.of(folder.strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Not a valid path: " + folder);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Folder not found: " + root);
        }

        long started = System.nanoTime();
        int added = 0;
        int updated = 0;
        int skipped = 0;
        int written = 0;

        for (Path file : filesIn(root)) {
            String path = file.toString();
            String content;
            try {
                content = read(file);
            } catch (RuntimeException ex) {
                skipped++;
                log.warn("skipping {}: {}", path, ex.toString());
                continue;
            }
            if (content == null) {
                skipped++;
                continue;
            }

            String hash = sha256(content);
            RagDocument document = documents.findByPath(path).orElse(null);
            if (document != null && hash.equals(document.getContentHash())) {
                skipped++;
                continue;
            }

            List<String> pieces = RagChunker.split(content, chunkSize, overlap);
            if (pieces.isEmpty()) {
                skipped++;
                continue;
            }
            List<float[]> vectors = embedAll(pieces);

            boolean replacing = document != null;
            if (document == null) {
                document = new RagDocument();
                document.setPath(path);
                added++;
            } else {
                updated++;
            }
            document.setTitle(file.getFileName().toString());
            document.setContentHash(hash);
            document.setSizeBytes(sizeOf(file));
            document.setChunkCount(pieces.size());
            document.setIndexedAt(java.time.Instant.now());
            documents.saveAndFlush(document);

            if (replacing) {
                // The new embeddings already exist, so replacing the old passages cannot leave the
                // document with nothing to retrieve.
                chunks.deleteByDocumentId(document.getId());
            }

            for (int ordinal = 0; ordinal < pieces.size(); ordinal++) {
                RagChunk chunk = new RagChunk();
                chunk.setDocument(document);
                chunk.setOrdinal(ordinal);
                chunk.setContent(pieces.get(ordinal));
                chunk.setEmbedding(serialize(vectors.get(ordinal)));
                chunks.save(chunk);
            }
            written += pieces.size();
        }

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        log.info("indexed {}: {} added, {} updated, {} skipped, {} chunks in {} ms",
                root, added, updated, skipped, written, elapsedMs);
        return new RagIndexResult(added, updated, skipped, written, elapsedMs, list());
    }

    @Transactional(readOnly = true)
    public List<RagDocumentView> list() {
        return documents.findAllByOrderByPathAsc().stream().map(RagDocumentView::from).toList();
    }

    /** How many files and chunks the index holds, for the UI's empty state. */
    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return documents.count() == 0;
    }

    @Transactional
    public void delete(UUID documentId) {
        RagDocument document = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document " + documentId + " is not indexed"));
        chunks.deleteByDocumentId(documentId);
        documents.delete(document);
    }

    /** The passages closest to the question, best first. */
    @Transactional(readOnly = true)
    public List<RagSnippet> search(String question, int limit) {
        if (question == null || question.isBlank() || limit <= 0) {
            return List.of();
        }
        List<RagChunk> all = chunks.findAllWithDocument();
        if (all.isEmpty()) {
            return List.of();
        }
        float[] query = embedder.embed(List.of(question)).getFirst();

        List<RagSnippet> scored = new ArrayList<>();
        for (RagChunk chunk : all) {
            double score = cosine(query, parse(chunk.getEmbedding()));
            if (score < minScore) {
                continue;
            }
            scored.add(new RagSnippet(
                    chunk.getDocument().getId(),
                    chunk.getDocument().getPath(),
                    chunk.getOrdinal(),
                    Math.round(score * 1_000) / 1_000.0,
                    chunk.getContent()));
        }
        scored.sort(Comparator.comparingDouble(RagSnippet::score).reversed());

        Map<UUID, Integer> perDocument = new HashMap<>();
        List<RagSnippet> top = new ArrayList<>();
        for (RagSnippet snippet : scored) {
            int used = perDocument.getOrDefault(snippet.documentId(), 0);
            if (used >= MAX_CHUNKS_PER_DOCUMENT) {
                continue;
            }
            perDocument.put(snippet.documentId(), used + 1);
            top.add(snippet);
            if (top.size() >= limit) {
                break;
            }
        }
        return top;
    }

    /**
     * The system message that carries retrieval into a prompt, or empty when nothing is indexed or
     * the embedding call fails. Retrieval is an enhancement: a missing embedding model must degrade
     * a chat to a plain chat, not break it.
     */
    @Transactional(readOnly = true)
    public Optional<String> contextFor(String question) {
        try {
            List<RagSnippet> snippets = search(question, topK);
            if (snippets.isEmpty()) {
                return Optional.empty();
            }
            StringBuilder block = new StringBuilder("""
                    Excerpts retrieved from the user's local document index. Use them when they answer \
                    the question, cite the file path in square brackets, and say so plainly when they \
                    do not.
                    """);
            for (int i = 0; i < snippets.size(); i++) {
                RagSnippet snippet = snippets.get(i);
                block.append('\n').append('[').append(i + 1).append("] ").append(snippet.path()).append('\n')
                        .append(limit(snippet.content())).append('\n');
            }
            return Optional.of(block.toString());
        } catch (RuntimeException ex) {
            log.warn("retrieval skipped: {}", ex.toString());
            return Optional.empty();
        }
    }

    private List<Path> filesIn(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(file -> supported(root, file))
                    .filter(file -> sizeOf(file) <= maxFileBytes)
                    .sorted()
                    .limit(maxFiles + 1L)
                    .toList();
            if (files.size() > maxFiles) {
                throw new IllegalArgumentException("Too many files under " + root
                        + "; index a smaller folder (limit " + maxFiles + ")");
            }
            return files;
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Could not read " + root + ": " + ex.getMessage());
        }
    }

    private static boolean supported(Path root, Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            return false;
        }
        Set<String> segments = new HashSet<>();
        for (Path segment : root.relativize(file)) {
            segments.add(segment.toString().toLowerCase(Locale.ROOT));
        }
        return segments.stream().noneMatch(IGNORED_DIRECTORIES::contains);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (java.io.IOException ex) {
            return Long.MAX_VALUE;
        }
    }

    /** Decoded text, or null for a file that turns out to be binary despite its extension. */
    private String read(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.indexOf('\0') >= 0 ? null : text;
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private List<float[]> embedAll(List<String> pieces) {
        List<float[]> vectors = new ArrayList<>(pieces.size());
        for (int from = 0; from < pieces.size(); from += EMBED_BATCH) {
            vectors.addAll(embedder.embed(pieces.subList(from, Math.min(from + EMBED_BATCH, pieces.size()))));
        }
        return vectors;
    }

    private static String limit(String text) {
        return text.length() <= PROMPT_CHARS ? text : text.substring(0, PROMPT_CHARS) + "…";
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / Math.sqrt(normA * normB);
    }

    static String serialize(float[] vector) {
        StringBuilder text = new StringBuilder(vector.length * 8);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vector[i]);
        }
        return text.toString();
    }

    static float[] parse(String embedding) {
        String[] parts = embedding.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
    }
}
