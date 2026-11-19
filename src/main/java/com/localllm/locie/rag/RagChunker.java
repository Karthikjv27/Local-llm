package com.localllm.locie.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document into overlapping windows.
 *
 * <p>An embedding of a whole file averages away the passage the question is about, so text is cut
 * into pieces of roughly {@code size} characters, overlapping by {@code overlap} so a sentence
 * that straddles a cut is still whole in one of them. The cut prefers a paragraph, then a line,
 * then a sentence, then a space - in that order, and never earlier than 60% of the window - so a
 * chunk rarely starts or ends mid-word.
 */
public final class RagChunker {

    private static final double MIN_BOUNDARY = 0.6;

    private RagChunker() {
    }

    public static List<String> split(String text, int size, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive");
        }
        int repeat = Math.max(0, Math.min(overlap, size - 1));
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + size, normalized.length());
            if (end < normalized.length()) {
                int boundary = boundary(normalized, start, end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            String chunk = normalized.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            // Advance past the overlap, but always by at least one character so a boundary that
            // lands early can never produce an infinite loop.
            start = Math.max(start + 1, end - repeat);
        }
        return chunks;
    }

    private static int boundary(String text, int start, int end) {
        int floor = start + Math.max(1, (int) ((end - start) * MIN_BOUNDARY));
        int paragraph = text.lastIndexOf("\n\n", end - 1);
        if (paragraph > floor) {
            return paragraph + 2;
        }
        int line = text.lastIndexOf('\n', end - 1);
        if (line > floor) {
            return line + 1;
        }
        int sentence = text.lastIndexOf(". ", end - 1);
        if (sentence > floor) {
            return sentence + 2;
        }
        int space = text.lastIndexOf(' ', end - 1);
        if (space > floor) {
            return space + 1;
        }
        return end;
    }
}
