package com.localllm.locie.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * The first {@link LlmProvider} adapter: Ollama, reached through its native HTTP API.
 *
 * <p>It owns the two transport clients and is the only place that knows Ollama's field names.
 * Everything above it works with the port's records, so a second runtime can be added beside this
 * class instead of inside the callers.
 */
@Component
public class OllamaLlmProvider implements LlmProvider {

    private final OllamaChatClient chat;
    private final OllamaAdminClient admin;

    public OllamaLlmProvider(ObjectMapper mapper,
                             @Value("${locie.ollama.base-url}") String baseUrl,
                             @Value("${locie.ollama.timeout:5s}") Duration timeout) {
        this.chat = new OllamaChatClient(mapper, baseUrl);
        this.admin = new OllamaAdminClient(baseUrl, timeout);
    }

    @Override
    public Flux<Chunk> stream(String model, List<Map<String, String>> messages) {
        return chat.stream(model, messages);
    }

    @Override
    public List<Model> models() {
        return admin.listModels().stream()
                .map(entry -> new Model(
                        entry.name(),
                        detail(entry.details()).map(OllamaAdminClient.ModelDetails::family).orElse(null),
                        detail(entry.details()).map(OllamaAdminClient.ModelDetails::parameterSize).orElse(null),
                        detail(entry.details()).map(OllamaAdminClient.ModelDetails::quantizationLevel).orElse(null),
                        entry.size(),
                        entry.modifiedAt()))
                .toList();
    }

    @Override
    public List<Running> running() {
        return admin.runningModels().stream()
                .map(entry -> new Running(
                        entry.name(),
                        detail(entry.details()).map(OllamaAdminClient.ModelDetails::family).orElse(null),
                        detail(entry.details()).map(OllamaAdminClient.ModelDetails::parameterSize).orElse(null),
                        detail(entry.details()).map(OllamaAdminClient.ModelDetails::quantizationLevel).orElse(null),
                        entry.size(),
                        entry.sizeVram(),
                        entry.expiresAt()))
                .toList();
    }

    @Override
    public Detail show(String model) {
        OllamaAdminClient.ShowResponse response = admin.showModel(model);
        OllamaAdminClient.ModelDetails details = response.details();
        return new Detail(
                details == null ? null : details.family(),
                details == null ? null : details.format(),
                details == null ? null : details.parameterSize(),
                details == null ? null : details.quantizationLevel(),
                response.capabilities() == null ? List.of() : response.capabilities(),
                response.system(),
                response.parameters(),
                response.modelInfo() == null ? Map.of() : response.modelInfo());
    }

    @Override
    public Optional<String> version() {
        return admin.version();
    }

    private static Optional<OllamaAdminClient.ModelDetails> detail(OllamaAdminClient.ModelDetails details) {
        return Optional.ofNullable(details);
    }
}
