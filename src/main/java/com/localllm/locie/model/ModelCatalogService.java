package com.localllm.locie.model;

import com.localllm.locie.model.OllamaAdminClient.ModelDetails;
import com.localllm.locie.model.OllamaAdminClient.ModelEntry;
import com.localllm.locie.model.OllamaAdminClient.RunningEntry;
import com.localllm.locie.model.OllamaAdminClient.ShowResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Combines Ollama's catalog and residency data into the shapes the UI consumes. */
@Service
public class ModelCatalogService {

    private final OllamaAdminClient ollama;

    public ModelCatalogService(OllamaAdminClient ollama) {
        this.ollama = ollama;
    }

    public List<ModelView> list() {
        Map<String, RunningEntry> running = ollama.runningModels().stream()
                .collect(Collectors.toMap(RunningEntry::name, Function.identity(), (a, b) -> a));

        return ollama.listModels().stream()
                .map(entry -> {
                    RunningEntry live = running.get(entry.name());
                    return new ModelView(
                            entry.name(),
                            family(entry),
                            parameterSize(entry),
                            quantization(entry),
                            entry.size(),
                            entry.modifiedAt(),
                            live != null,
                            live == null ? null : live.sizeVram(),
                            live == null ? null : live.expiresAt());
                })
                .toList();
    }

    public List<RunningModelView> running() {
        return ollama.runningModels().stream()
                .map(entry -> new RunningModelView(
                        entry.name(),
                        family(entry),
                        parameterSize(entry),
                        quantization(entry),
                        entry.size(),
                        entry.sizeVram(),
                        gpuPercent(entry.size(), entry.sizeVram()),
                        entry.expiresAt()))
                .toList();
    }

    /** Ollama version reported by the daemon, or null when it is unreachable. */
    public String ollamaVersion() {
        return ollama.version().orElse(null);
    }

    public ModelDetailView show(String name) {
        ShowResponse response = ollama.showModel(name);
        ModelDetails details = response.details();
        return new ModelDetailView(
                name,
                details == null ? null : details.family(),
                details == null ? null : details.format(),
                details == null ? null : details.parameterSize(),
                details == null ? null : details.quantizationLevel(),
                response.capabilities() == null ? List.of() : response.capabilities(),
                response.system(),
                response.parameters(),
                response.modelInfo() == null ? Map.of() : response.modelInfo());
    }

    /** Share of the model's weights that Ollama reports as resident in VRAM. */
    private static double gpuPercent(long sizeBytes, long vramBytes) {
        if (sizeBytes <= 0) {
            return 0;
        }
        return Math.round((double) vramBytes / sizeBytes * 1000) / 10.0;
    }

    private static String family(ModelEntry entry) {
        return entry.details() == null ? null : entry.details().family();
    }

    private static String family(RunningEntry entry) {
        return entry.details() == null ? null : entry.details().family();
    }

    private static String parameterSize(ModelEntry entry) {
        return entry.details() == null ? null : entry.details().parameterSize();
    }

    private static String parameterSize(RunningEntry entry) {
        return entry.details() == null ? null : entry.details().parameterSize();
    }

    private static String quantization(ModelEntry entry) {
        return entry.details() == null ? null : entry.details().quantizationLevel();
    }

    private static String quantization(RunningEntry entry) {
        return entry.details() == null ? null : entry.details().quantizationLevel();
    }
}
