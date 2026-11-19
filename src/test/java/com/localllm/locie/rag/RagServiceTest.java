package com.localllm.locie.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class RagServiceTest {

    private final RagDocumentRepository documents = mock(RagDocumentRepository.class);
    private final RagChunkRepository chunks = mock(RagChunkRepository.class);
    private final FakeEmbedder embedder = new FakeEmbedder();
    private final RagService service = new RagService(
            documents, chunks, embedder, 400, 80, 5, 0.3, 100_000, 50);

    @TempDir
    Path folder;

    @BeforeEach
    void setUp() {
        when(documents.findByPath(any())).thenReturn(Optional.empty());
        when(documents.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(documents.findAllByOrderByPathAsc()).thenReturn(List.of());
    }

    @Test
    void indexingAWritesTheDocumentAndItsChunks() throws IOException {
        Files.writeString(folder.resolve("notes.md"), "alpha paragraph\n\n" + "beta ".repeat(200));
        Files.writeString(folder.resolve("logo.png"), "not text");
        Files.createDirectories(folder.resolve("node_modules"));
        Files.writeString(folder.resolve("node_modules/skip.md"), "alpha");

        RagIndexResult result = service.index(folder.toString());

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.chunks()).isGreaterThan(1);
        ArgumentCaptor<RagDocument> savedDocument = ArgumentCaptor.forClass(RagDocument.class);
        verify(documents).saveAndFlush(savedDocument.capture());
        assertThat(savedDocument.getValue().getPath()).endsWith("notes.md");
        assertThat(savedDocument.getValue().getChunkCount()).isEqualTo(result.chunks());

        ArgumentCaptor<RagChunk> savedChunks = ArgumentCaptor.forClass(RagChunk.class);
        verify(chunks, atLeast(2)).save(savedChunks.capture());
        assertThat(savedChunks.getAllValues().getFirst().getOrdinal()).isZero();
        assertThat(savedChunks.getAllValues()).allSatisfy(chunk ->
                assertThat(chunk.getEmbedding()).isNotBlank());
    }

    @Test
    void anUnchangedFileIsSkippedWithoutCallingTheEmbedder() throws IOException {
        Path file = folder.resolve("notes.md");
        Files.writeString(file, "alpha note");
        String path = file.toAbsolutePath().normalize().toString();
        when(documents.findByPath(path))
                .thenReturn(Optional.of(document(path, "notes.md", "alpha note")));

        RagIndexResult result = service.index(folder.toString());

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.added()).isZero();
        assertThat(embedder.calls).isZero();
        verify(chunks, never()).save(any());
        verify(documents, never()).saveAndFlush(any());
    }

    @Test
    void searchRanksByCosineAndCapsEachDocument() {
        RagDocument alpha = document("a.md", "a.md", "");
        when(chunks.findAllWithDocument()).thenReturn(List.of(
                chunk(alpha, 0, "alpha one", new float[]{1, 0}),
                chunk(alpha, 1, "alpha two", new float[]{1, 0}),
                chunk(alpha, 2, "alpha three", new float[]{1, 0}),
                chunk(document("b.md", "b.md", ""), 0, "unrelated", new float[]{0, 1})));

        List<RagSnippet> found = service.search("where is alpha?", 5);

        assertThat(found).hasSize(2);
        assertThat(found).allSatisfy(snippet -> {
            assertThat(snippet.path()).isEqualTo("a.md");
            assertThat(snippet.score()).isEqualTo(1.0);
        });
    }

    @Test
    void passagesThatScoreBelowTheThresholdAreNotRetrieved() {
        when(chunks.findAllWithDocument()).thenReturn(List.of(
                chunk(document("a.md", "a.md", ""), 0, "unrelated", new float[]{0, 1})));

        assertThat(service.search("where is alpha?", 5)).isEmpty();
        assertThat(service.contextFor("where is alpha?")).isEmpty();
    }

    @Test
    void theContextBlockNamesTheSourceAndItsText() {
        when(chunks.findAllWithDocument()).thenReturn(List.of(
                chunk(document("a.md", "a.md", ""), 0, "alpha one", new float[]{1, 0})));

        assertThat(service.contextFor("where is alpha?")).hasValueSatisfying(context -> {
            assertThat(context).contains("[1] a.md").contains("alpha one");
        });
    }

    @Test
    void aFailedEmbeddingLeavesTheChatWithoutRetrieval() {
        when(chunks.findAllWithDocument()).thenReturn(List.of(
                chunk(document("a.md", "a.md", ""), 0, "alpha", new float[]{1, 0})));
        embedder.fail = true;

        assertThat(service.contextFor("where is alpha?")).isEmpty();
    }

    @Test
    void aMissingFolderIsRejected() {
        assertThatThrownBy(() -> service.index(folder.resolve("missing").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Folder not found");
    }

    @Test
    void deletingADocumentRemovesItsChunks() {
        RagDocument document = document("a.md", "a.md", "");
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));

        service.delete(document.getId());

        verify(chunks).deleteByDocumentId(document.getId());
        verify(documents).delete(document);
    }

    private static RagDocument document(String path, String title, String content) {
        RagDocument document = new RagDocument();
        document.setId(UUID.randomUUID());
        document.setPath(path);
        document.setTitle(title);
        document.setContentHash(RagService.sha256(content));
        document.setChunkCount(1);
        return document;
    }

    private static RagChunk chunk(RagDocument document, int ordinal, String content, float[] vector) {
        RagChunk chunk = new RagChunk();
        chunk.setId(UUID.randomUUID());
        chunk.setDocument(document);
        chunk.setOrdinal(ordinal);
        chunk.setContent(content);
        chunk.setEmbedding(RagService.serialize(vector));
        return chunk;
    }

    /** Deterministic stand-in for the embedding model: "alpha" points one way, the rest the other. */
    private static final class FakeEmbedder implements Embedder {

        private int calls;
        private boolean fail;

        @Override
        public List<float[]> embed(List<String> texts) {
            calls++;
            if (fail) {
                throw new com.localllm.locie.common.OllamaUnavailableException("embedding model is down");
            }
            return texts.stream()
                    .map(text -> text.toLowerCase().contains("alpha") ? new float[]{1f, 0f} : new float[]{0f, 1f})
                    .toList();
        }

        @Override
        public String model() {
            return "fake-embed";
        }
    }
}
