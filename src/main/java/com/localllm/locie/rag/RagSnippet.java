package com.localllm.locie.rag;

import java.util.UUID;

/** A retrieved passage: where it came from, how close it scored, and the text itself. */
public record RagSnippet(
        UUID documentId,
        String path,
        int ordinal,
        double score,
        String content) {
}
