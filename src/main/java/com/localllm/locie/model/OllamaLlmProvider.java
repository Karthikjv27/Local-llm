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
    public Flux<Chunk> stream(String model, List<Map<String, Object>> messages, List<Tool> tools) {
        return chat.stream(model, messages, tools);
    }

    @Override
    public List<Model> models() {
        return admin.listModels().stream()
                .map(entry -> {
                    ModelFacts facts = ModelFacts.of(entry.details());
                    return new Model(
                            entry.name(),
                            facts.family(),
                            facts.parameterSize(),
                            facts.quantization(),
                            entry.size(),
                            entry.modifiedAt());
                })
                .toList();
    }

    @Override
    public List<Running> running() {
        return admin.runningModels().stream()
                .map(entry -> {
                    ModelFacts facts = ModelFacts.of(entry.details());
                    return new Running(
                            entry.name(),
                            facts.family(),
                            facts.parameterSize(),
                            facts.quantization(),
                            entry.size(),
                            entry.sizeVram(),
                            entry.expiresAt());
                })
                .toList();
    }

    @Override
    public Detail show(String model) {
        OllamaAdminClient.ShowResponse response = admin.showModel(model);
        ModelFacts facts = ModelFacts.of(response.details());
        return new Detail(
                facts.family(),
                facts.format(),
                facts.parameterSize(),
                facts.quantization(),
                response.capabilities() == null ? List.of() : response.capabilities(),
                response.system(),
                response.parameters(),
                response.modelInfo() == null ? Map.of() : response.modelInfo());
    }

    @Override
    public Optional<String> version() {
        return admin.version();
    }

    /**
     * Ollama answers "no details" with a null object rather than absent fields, and the same three
     * facts are needed in every mapping - so they are read once, here.
     */
    private record ModelFacts(String family, String format, String parameterSize, String quantization) {

        static ModelFacts of(OllamaAdminClient.ModelDetails details) {
            return details == null
                    ? new ModelFacts(null, null, null, null)
                    : new ModelFacts(details.family(), details.format(),
                            details.parameterSize(), details.quantizationLevel());
        }
    }
}
