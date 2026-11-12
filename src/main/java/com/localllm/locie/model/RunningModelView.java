package com.localllm.locie.model;

import java.time.Instant;

public record RunningModelView(
        String name,
        String family,
        String parameterSize,
        String quantization,
        long sizeBytes,
        long vramBytes,
        double gpuPercent,
        Instant expiresAt) {
}
