package com.localllm.locie.model;

import com.localllm.locie.common.OllamaModelNotFoundException;
import com.localllm.locie.common.OllamaUnavailableException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Streaming chat through Ollama's native {@code POST /api/chat} endpoint.
 *
 * <p>Spring AI's {@code ChatClient} is deliberately not used here: its streaming aggregator drops
 * Ollama's native usage payload, so {@code eval_count}/{@code eval_duration} - the exact throughput
 * counters - never reach the caller. Reading the NDJSON frames directly preserves them.
 */
@Component
public class OllamaChatClient {

    private final WebClient webClient;
    private final ObjectMapper mapper;

    public OllamaChatClient(ObjectMapper mapper, @Value("${locie.ollama.base-url}") String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.mapper = mapper;
    }

    public Flux<OllamaStreamChunk> stream(String model, List<Map<String, String>> messages) {
        Map<String, Object> payload = Map.of("model", model, "messages", messages, "stream", true);

        return Flux.defer(() -> {
            ByteArrayOutputStream carry = new ByteArrayOutputStream();
            return webClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_NDJSON, MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .exchangeToFlux(response -> {
                        if (response.statusCode().isError()) {
                            return response.createException().flatMapMany(ex -> Flux.error(mapError(ex)));
                        }
                        return response.bodyToFlux(DataBuffer.class);
                    })
                    .concatMap(buffer -> Flux.fromIterable(takeLines(carry, buffer)))
                    .concatWith(Flux.defer(() -> Flux.fromIterable(takeRest(carry))))
                    .map(this::parse)
                    .onErrorMap(WebClientRequestException.class,
                            ex -> new OllamaUnavailableException("Ollama is unreachable", ex));
        });
    }

    /**
     * Appends the buffer's bytes and returns every complete line. Framing happens on bytes so a
     * multi-byte UTF-8 character split across two network buffers is never corrupted.
     */
    private static List<String> takeLines(ByteArrayOutputStream carry, DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            carry.write(bytes, 0, bytes.length);
        } finally {
            DataBufferUtils.release(buffer);
        }

        byte[] all = carry.toByteArray();
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == '\n') {
                String line = new String(all, start, i - start, StandardCharsets.UTF_8).strip();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
                start = i + 1;
            }
        }
        carry.reset();
        carry.write(all, start, all.length - start);
        return lines;
    }

    /** A trailing frame without a final newline still counts. */
    private static List<String> takeRest(ByteArrayOutputStream carry) {
        String rest = new String(carry.toByteArray(), StandardCharsets.UTF_8).strip();
        carry.reset();
        return rest.isEmpty() ? List.of() : List.of(rest);
    }

    private OllamaStreamChunk parse(String line) {
        JsonNode node;
        try {
            node = mapper.readTree(line);
        } catch (JacksonException ex) {
            throw new OllamaUnavailableException("Ollama sent an unreadable stream frame", ex);
        }
        if (node.hasNonNull("error")) {
            throw new OllamaUnavailableException(node.get("error").asText());
        }
        return new OllamaStreamChunk(
                node.path("message").path("content").asText(""),
                node.path("done").asBoolean(false),
                node.path("done_reason").asText(null),
                intOrNull(node, "prompt_eval_count"),
                intOrNull(node, "eval_count"),
                durationOrNull(node, "eval_duration"),
                durationOrNull(node, "load_duration"),
                durationOrNull(node, "total_duration"));
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    private static Duration durationOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? Duration.ofNanos(node.get(field).asLong()) : null;
    }

    private static RuntimeException mapError(WebClientResponseException ex) {
        if (ex.getStatusCode().value() == 404) {
            return new OllamaModelNotFoundException("Model not found in Ollama", ex);
        }
        return new OllamaUnavailableException("Ollama responded with " + ex.getStatusCode().value(), ex);
    }
}
