package com.localllm.locie.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RagChunkerTest {

    @Test
    void blankTextProducesNoChunks() {
        assertThat(RagChunker.split("   \n\n \t ", 100, 20)).isEmpty();
        assertThat(RagChunker.split(null, 100, 20)).isEmpty();
    }

    @Test
    void shortTextStaysOneChunk() {
        assertThat(RagChunker.split("a short note", 100, 20)).containsExactly("a short note");
    }

    @Test
    void longTextIsCutIntoWindowsThatNeverExceedTheSize() {
        List<String> chunks = RagChunker.split("word ".repeat(400), 200, 40);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(200));
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).isNotBlank());
    }

    @Test
    void aParagraphBoundaryIsPreferredOverAHardCut() {
        String text = "a".repeat(70) + "\n\n" + "b".repeat(70);

        List<String> chunks = RagChunker.split(text, 100, 0);

        assertThat(chunks.get(0)).doesNotContain("b");
        assertThat(chunks.get(1)).startsWith("b");
    }

    @Test
    void consecutiveChunksOverlap() {
        String text = "abcdefghij".repeat(5);

        List<String> chunks = RagChunker.split(text, 20, 5);

        assertThat(chunks.size()).isGreaterThan(1);
        String previousTail = chunks.get(0).substring(chunks.get(0).length() - 5);
        assertThat(chunks.get(1)).startsWith(previousTail);
    }

    @Test
    void anOverlapLargerThanTheWindowStillTerminates() {
        List<String> chunks = RagChunker.split("x".repeat(120), 40, 1_000);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.getLast().length()).isLessThanOrEqualTo(40);
    }
}
