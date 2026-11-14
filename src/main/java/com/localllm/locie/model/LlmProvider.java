package com.localllm.locie.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Flux;

/**
 * The seam between Locie and whatever is running the models.
 *
 * <p>Nothing outside {@code model} knows how a provider is reached: {@code ChatService} and
 * {@code ModelCatalogService} depend on this interface, and the Ollama implementation is one
 * adapter behind it. Adding a second runtime means adding an implementation, not editing callers.
 *
 * <p>Types are provider-neutral on purpose - the records below describe a chunk, a model and its
 * residency, not Ollama's wire format.
 */
public interface LlmProvider {

    /** Streams a chat completion. Frames arrive in order; the last one has {@code done = true}. */
    Flux<Chunk> stream(String model, List<Map<String, String>> messages);

    /** Models installed locally, with no residency information. */
    List<Model> models();

    /** Models currently loaded, including how much of them is resident in VRAM. */
    List<Running> running();

    /** Full metadata for one model. */
    Detail show(String model);

    /** Provider version, or empty when the runtime cannot be reached. */
    Optional<String> version();

    /**
     * One streamed frame. The final frame carries the counters, which is where exact throughput
     * comes from: {@code evalCount} tokens produced in {@code evalDuration}.
     */
    record Chunk(
            String content,
            boolean done,
            String doneReason,
            Integer promptEvalCount,
            Integer evalCount,
            Duration evalDuration,
            Duration loadDuration,
            Duration totalDuration) {
    }

    record Model(
            String name,
            String family,
            String parameterSize,
            String quantization,
            long sizeBytes,
            Instant modifiedAt) {
    }

    record Running(
            String name,
            String family,
            String parameterSize,
            String quantization,
            long sizeBytes,
            long vramBytes,
            Instant expiresAt) {
    }

    record Detail(
            String family,
            String format,
            String parameterSize,
            String quantization,
            List<String> capabilities,
            String system,
            String parameters,
            Map<String, Object> modelInfo) {
    }
}
