package com.localllm.locie.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.localllm.locie.common.OllamaModelNotFoundException;
import com.localllm.locie.common.OllamaUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Embeddings from Ollama's native {@code POST /api/embed}.
 *
 * <p>The read timeout is deliberately long: the first call of a session also loads the embedding
 * model, which can take seconds on a small card.
 */
@Component
public class OllamaEmbedder implements Embedder {

    private final RestClient client;
    private final String model;

    public OllamaEmbedder(@Value("${locie.ollama.base-url:http://localhost:11434}") String baseUrl,
                          @Value("${locie.rag.embedding-model:nomic-embed-text}") String model) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.model = model;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        EmbedResponse response;
        try {
            response = client.post()
                    .uri("/api/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "input", texts))
                    .retrieve()
                    .body(EmbedResponse.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new OllamaModelNotFoundException(
                        "Embedding model '" + model + "' is not installed; run `ollama pull " + model + "`", ex);
            }
            throw new OllamaUnavailableException(
                    "Ollama responded with " + ex.getStatusCode().value() + " while embedding", ex);
        } catch (ResourceAccessException ex) {
            throw new OllamaUnavailableException("Ollama is unreachable", ex);
        }

        if (response == null || response.embeddings() == null
                || response.embeddings().size() != texts.size()) {
            throw new OllamaUnavailableException(
                    "Ollama returned " + (response == null || response.embeddings() == null
                            ? "no embeddings" : response.embeddings().size() + " embeddings for "
                            + texts.size() + " inputs"));
        }
        return response.embeddings().stream().map(OllamaEmbedder::toFloats).toList();
    }

    private static float[] toFloats(List<Double> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbedResponse(List<List<Double>> embeddings) {
    }
}
