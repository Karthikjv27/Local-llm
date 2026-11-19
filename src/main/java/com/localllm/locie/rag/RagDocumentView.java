package com.localllm.locie.rag;

import java.time.Instant;
import java.util.UUID;

/** One indexed file as the UI sees it. */
public record RagDocumentView(
        UUID id,
        String path,
        String title,
        long sizeBytes,
        int chunks,
        Instant indexedAt) {

    static RagDocumentView from(RagDocument document) {
        return new RagDocumentView(
                document.getId(),
                document.getPath(),
                document.getTitle(),
                document.getSizeBytes(),
                document.getChunkCount(),
                document.getIndexedAt());
    }
}
