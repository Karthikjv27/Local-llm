package com.localllm.locie.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Loads a golden set and runs it through the real retrieval path.
 *
 * <p>The fixture is one case per line, {@code question<TAB>expected path}. A line format was chosen
 * over JSON deliberately: an expected path on Windows is full of backslashes that JSON would need
 * escaped, and a plain line diffs and edits better in a text editor. Blank lines and lines starting
 * with {@code #} are ignored.
 *
 * <p>Every question goes through {@code RagService.search} with the same {@code k} the chat path
 * uses, so the report measures what a reply would actually be shown.
 */
@Slf4j
@Service
public class RagEvalService {

    /** A cap on k, so a typo cannot ask retrieval for the entire index on every question. */
    private static final int MAX_K = 50;

    private final RagService rag;
    private final String configuredFixture;
    private final int defaultK;

    public RagEvalService(RagService rag,
                          @Value("${locie.rag.eval-fixture:}") String configuredFixture,
                          @Value("${locie.rag.top-k:5}") int defaultK) {
        this.rag = rag;
        this.configuredFixture = configuredFixture;
        this.defaultK = defaultK;
    }

    /**
     * Grades retrieval against the fixture at {@code path}, or against the configured fixture when
     * no path is given, and returns recall@k, MRR and each question's rank.
     */
    public RagEvalReport evaluate(String path, Integer requestedK) {
        Path file = fixtureFile(path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Eval fixture not found: " + file);
        }
        int k = k(requestedK);
        List<RagEvalCase> cases = read(file);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("Eval fixture has no cases: " + file);
        }

        RagEvalReport report = RagEvaluator.evaluate(k, cases, question -> rag.search(question, k));
        log.info("retrieval eval on {} cases from {}: recall@{} {}, MRR {}",
                report.total(), file, report.k(), report.recallAtK(), report.mrr());
        return report;
    }

    private Path fixtureFile(String path) {
        String chosen = path == null || path.isBlank() ? configuredFixture : path;
        if (chosen == null || chosen.isBlank()) {
            throw new IllegalArgumentException(
                    "No eval fixture configured; set locie.rag.eval-fixture or pass ?path=");
        }
        try {
            return Path.of(chosen.strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Not a valid path: " + chosen);
        }
    }

    private int k(Integer requestedK) {
        int chosen = requestedK == null ? defaultK : requestedK;
        if (chosen < 1 || chosen > MAX_K) {
            throw new IllegalArgumentException("k must be between 1 and " + MAX_K);
        }
        return chosen;
    }

    private List<RagEvalCase> read(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "Could not read eval fixture " + file + ": " + ex.getMessage());
        }

        List<RagEvalCase> cases = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                throw new IllegalArgumentException("Eval fixture line " + (i + 1)
                        + " needs a TAB between the question and the expected path");
            }
            String question = line.substring(0, tab).strip();
            String expected = line.substring(tab + 1).strip();
            if (question.isEmpty() || expected.isEmpty()) {
                throw new IllegalArgumentException("Eval fixture line " + (i + 1)
                        + " needs both a question and an expected path");
            }
            cases.add(new RagEvalCase(question, expected));
        }
        return cases;
    }
}
