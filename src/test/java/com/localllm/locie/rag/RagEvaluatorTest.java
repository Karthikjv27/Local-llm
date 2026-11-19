package com.localllm.locie.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RagEvaluatorTest {

    private static final RagEvalCase PRUNING =
            new RagEvalCase("where is pruning done?", "rag/RagService.java");
    private static final RagEvalCase CHUNKING =
            new RagEvalCase("how is text split?", "rag/RagChunker.java");

    @Test
    void aFileAtTheTopOfTheListScoresOneForBothMetrics() {
        RagEvalReport report = RagEvaluator.evaluate(5, List.of(PRUNING), question -> List.of(
                snippet("D:/locie/locie/src/main/java/com/localllm/locie/rag/RagService.java")));

        assertThat(report.k()).isEqualTo(5);
        assertThat(report.total()).isEqualTo(1);
        assertThat(report.hits()).isEqualTo(1);
        assertThat(report.recallAtK()).isEqualTo(1.0);
        assertThat(report.mrr()).isEqualTo(1.0);
        assertThat(report.cases().getFirst().rank()).isEqualTo(1);
        assertThat(report.cases().getFirst().path()).endsWith("RagService.java");
    }

    @Test
    void aMissIsRecordedAsRankZeroWithNoPath() {
        RagEvalReport report = RagEvaluator.evaluate(5, List.of(PRUNING),
                question -> List.of(snippet("D:/notes/unrelated.md")));

        assertThat(report.hits()).isZero();
        assertThat(report.recallAtK()).isZero();
        assertThat(report.mrr()).isZero();
        assertThat(report.cases().getFirst().rank()).isZero();
        assertThat(report.cases().getFirst().path()).isNull();
    }

    @Test
    void nothingRetrievedIsAMiss() {
        RagEvalReport report = RagEvaluator.evaluate(5, List.of(PRUNING), question -> List.of());

        assertThat(report.recallAtK()).isZero();
        assertThat(report.cases().getFirst().rank()).isZero();
    }

    @Test
    void mrrAveragesTheReciprocalRanks() {
        RagEvalReport report = RagEvaluator.evaluate(5, List.of(PRUNING, CHUNKING),
                question -> question.startsWith("where")
                        ? List.of(snippet("D:/notes/rag/RagService.java"))
                        : List.of(snippet("D:/notes/a.md"),
                                snippet("D:/notes/b.md"),
                                snippet("D:/notes/rag/RagChunker.java")));

        assertThat(report.recallAtK()).isEqualTo(1.0);
        // (1/1 + 1/3) / 2
        assertThat(report.mrr()).isEqualTo(0.667);
    }

    @Test
    void ranksCountDistinctFilesNotPassages() {
        RagEvalReport report = RagEvaluator.evaluate(5, List.of(CHUNKING), question -> List.of(
                snippet("D:/notes/a.md"),
                snippet("D:/notes/a.md"),
                snippet("D:/notes/a.md"),
                snippet("D:/notes/rag/RagChunker.java")));

        assertThat(report.cases().getFirst().rank()).isEqualTo(2);
        assertThat(report.mrr()).isEqualTo(0.5);
    }

    @Test
    void aFileBelowTheFirstKIsAMiss() {
        RagEvalReport report = RagEvaluator.evaluate(2, List.of(CHUNKING), question -> List.of(
                snippet("D:/notes/a.md"),
                snippet("D:/notes/b.md"),
                snippet("D:/notes/rag/RagChunker.java")));

        assertThat(report.hits()).isZero();
        assertThat(report.cases().getFirst().rank()).isZero();
    }

    @Test
    void expectedPathsMatchBySuffixInEitherSeparatorStyle() {
        assertThat(RagEvaluator.matches(
                "D:\\notes\\rag\\RagChunker.java", "rag\\RagChunker.java")).isTrue();
        assertThat(RagEvaluator.matches(
                "D:/notes/rag/RagChunker.java", "/rag/RagChunker.java")).isTrue();
        assertThat(RagEvaluator.matches(
                "D:/notes/rag/RagChunker.java", "./rag/RagChunker.java")).isTrue();
        assertThat(RagEvaluator.matches(
                "D:\\notes\\rag\\RagChunker.java", "src/rag/RagChunker.java")).isFalse();
    }

    @Test
    void aPartialFileNameIsNotAMatch() {
        assertThat(RagEvaluator.matches("D:/notes/rag/RagChunker.java", "Chunker.java")).isFalse();
        assertThat(RagEvaluator.matches("D:/notes/rag/RagChunker.java", "RagChunker.java")).isTrue();
    }

    private static RagSnippet snippet(String path) {
        return new RagSnippet(UUID.randomUUID(), path, 0, 0.9, "text");
    }
}
