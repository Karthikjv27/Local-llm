package com.localllm.locie.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.localllm.locie.common.OllamaModelNotFoundException;
import com.localllm.locie.common.OllamaUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin client over Ollama's native HTTP API. A transport detail of {@link OllamaLlmProvider}.
 *
 * <p>Connectivity failures and unknown models are translated into typed exceptions so the API
 * layer can answer 503 / 404 instead of 500.
 */
class OllamaAdminClient {

    private final RestClient client;

    OllamaAdminClient(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Ollama's reported version, or empty when the daemon is unreachable. */
    Optional<String> version() {
        try {
            VersionResponse response = client.get().uri("/api/version").retrieve().body(VersionResponse.class);
            return response == null ? Optional.empty() : Optional.ofNullable(response.version());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** Installed models, from {@code GET /api/tags}. */
    List<ModelEntry> listModels() {
        TagsResponse response = call(() -> client.get().uri("/api/tags").retrieve().body(TagsResponse.class));
        return response == null || response.models() == null ? List.of() : response.models();
    }

    /** Currently loaded models and their VRAM residency, from {@code GET /api/ps}. */
    List<RunningEntry> runningModels() {
        PsResponse response = call(() -> client.get().uri("/api/ps").retrieve().body(PsResponse.class));
        return response == null || response.models() == null ? List.of() : response.models();
    }

    /** Full model metadata, from {@code POST /api/show}. */
    ShowResponse showModel(String name) {
        ShowResponse response = call(() -> client.post()
                .uri("/api/show")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", name))
                .retrieve()
                .body(ShowResponse.class));
        if (response == null) {
            throw new OllamaUnavailableException("Ollama returned an empty response for model " + name);
        }
        return response;
    }

    private <T> T call(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new OllamaModelNotFoundException("Model not found in Ollama", ex);
            }
            throw new OllamaUnavailableException("Ollama responded with " + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw new OllamaUnavailableException("Ollama is unreachable", ex);
        }
    }

    record VersionResponse(String version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TagsResponse(List<ModelEntry> models) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelEntry(
            String name,
            String model,
            @JsonProperty("modified_at") Instant modifiedAt,
            long size,
            String digest,
            ModelDetails details) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PsResponse(List<RunningEntry> models) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RunningEntry(
            String name,
            String model,
            long size,
            String digest,
            ModelDetails details,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("size_vram") long sizeVram) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelDetails(
            @JsonProperty("parent_model") String parentModel,
            String format,
            String family,
            List<String> families,
            @JsonProperty("parameter_size") String parameterSize,
            @JsonProperty("quantization_level") String quantizationLevel) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShowResponse(
            String license,
            String modelfile,
            String parameters,
            String template,
            String system,
            ModelDetails details,
            @JsonProperty("model_info") Map<String, Object> modelInfo,
            List<String> capabilities) {
    }
}
