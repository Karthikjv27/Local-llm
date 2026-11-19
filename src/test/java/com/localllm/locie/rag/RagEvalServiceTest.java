package com.localllm.locie.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagEvalServiceTest {

    private final RagService rag = mock(RagService.class);
    private final RagEvalService service = new RagEvalService(rag, "", 5);

    @TempDir
    Path folder;

    @Test
    void theReportGradesEachQuestionAgainstWhatRetrievalReturned() throws IOException {
        Path fixture = fixture("""
                # comments and blank lines are skipped

                where is pruning done?\trag/RagService.java
                how is text split?\trag/RagChunker.java
                """);
        when(rag.search("where is pruning done?", 5)).thenReturn(List.of(
                snippet("D:/notes/rag/RagService.java"), snippet("D:/notes/other.md")));
        when(rag.search("how is text split?", 5)).thenReturn(List.of(snippet("D:/notes/other.md")));

        RagEvalReport report = service.evaluate(fixture.toString(), null);

        assertThat(report.k()).isEqualTo(5);
        assertThat(report.total()).isEqualTo(2);
        assertThat(report.hits()).isEqualTo(1);
        assertThat(report.recallAtK()).isEqualTo(0.5);
        assertThat(report.mrr()).isEqualTo(0.5);
        assertThat(report.cases()).extracting(RagEvalCaseResult::rank).containsExactly(1, 0);
    }

    @Test
    void anExplicitKIsUsedForRetrieval() throws IOException {
        Path fixture = fixture("where is pruning done?\tRagService.java\n");
        when(rag.search(any(), eq(3))).thenReturn(List.of());

        assertThat(service.evaluate(fixture.toString(), 3).k()).isEqualTo(3);
        verify(rag).search("where is pruning done?", 3);
    }

    @Test
    void theConfiguredFixtureIsUsedWhenNoPathIsGiven() throws IOException {
        Path fixture = fixture("where is pruning done?\tRagService.java\n");
        RagEvalService configured = new RagEvalService(rag, fixture.toString(), 5);
        when(rag.search(any(), eq(5))).thenReturn(List.of(snippet("D:/notes/RagService.java")));

        assertThat(configured.evaluate(null, null).hits()).isEqualTo(1);
    }

    @Test
    void withoutAFixtureItSaysHowToPointAtOne() {
        assertThatThrownBy(() -> service.evaluate(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No eval fixture configured");
    }

    @Test
    void aMissingFixtureFileIsRejected() {
        assertThatThrownBy(() -> service.evaluate(folder.resolve("missing.txt").toString(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Eval fixture not found");
    }

    @Test
    void aLineWithoutATabIsRejected() throws IOException {
        Path fixture = fixture("where is pruning done?\n");

        assertThatThrownBy(() -> service.evaluate(fixture.toString(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a TAB");
    }

    @Test
    void anOutOfRangeKIsRejected() throws IOException {
        Path fixture = fixture("where is pruning done?\tRagService.java\n");

        assertThatThrownBy(() -> service.evaluate(fixture.toString(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k must be between");
    }

    private Path fixture(String content) throws IOException {
        Path file = folder.resolve("eval.txt");
        Files.writeString(file, content);
        return file;
    }

    private static RagSnippet snippet(String path) {
        return new RagSnippet(UUID.randomUUID(), path, 0, 0.9, "text");
    }
}
