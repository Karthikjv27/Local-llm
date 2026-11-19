package com.localllm.locie.rag;

import java.util.List;

/** What one indexing run did, plus the index as it now stands. */
public record RagIndexResult(
        int added,
        int updated,
        int skipped,
        int chunks,
        long durationMs,
        List<RagDocumentView> documents) {
}
