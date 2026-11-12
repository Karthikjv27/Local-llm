package com.localllm.locie.model;

import java.time.Instant;

public record ModelView(
        String name,
        String family,
        String parameterSize,
        String quantization,
        long sizeBytes,
        Instant modifiedAt,
        boolean running,
        Long vramBytes,
        Instant expiresAt) {
}
