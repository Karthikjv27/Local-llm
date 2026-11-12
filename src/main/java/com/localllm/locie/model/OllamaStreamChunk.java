package com.localllm.locie.model;

import java.time.Duration;

/**
 * One NDJSON frame from Ollama's {@code /api/chat} stream. The final frame ({@code done=true})
 * carries the timings, which is where exact throughput comes from.
 */
public record OllamaStreamChunk(
        String content,
        boolean done,
        String doneReason,
        Integer promptEvalCount,
        Integer evalCount,
        Duration evalDuration,
        Duration loadDuration,
        Duration totalDuration) {
}
