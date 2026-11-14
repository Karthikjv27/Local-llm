package com.localllm.locie.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Combines the provider's catalog and residency data into the shapes the UI consumes. */
@Service
public class ModelCatalogService {

    private final LlmProvider provider;

    public ModelCatalogService(LlmProvider provider) {
        this.provider = provider;
    }

    public List<ModelView> list() {
        Map<String, LlmProvider.Running> running = provider.running().stream()
                .collect(Collectors.toMap(LlmProvider.Running::name, Function.identity(), (a, b) -> a));

        return provider.models().stream()
                .map(model -> {
                    LlmProvider.Running live = running.get(model.name());
                    return new ModelView(
                            model.name(),
                            model.family(),
                            model.parameterSize(),
                            model.quantization(),
                            model.sizeBytes(),
                            model.modifiedAt(),
                            live != null,
                            live == null ? null : live.vramBytes(),
                            live == null ? null : live.expiresAt());
                })
                .toList();
    }

    public List<RunningModelView> running() {
        return provider.running().stream()
                .map(entry -> new RunningModelView(
                        entry.name(),
                        entry.family(),
                        entry.parameterSize(),
                        entry.quantization(),
                        entry.sizeBytes(),
                        entry.vramBytes(),
                        gpuPercent(entry.sizeBytes(), entry.vramBytes()),
                        entry.expiresAt()))
                .toList();
    }

    /** Provider version reported by the runtime, or null when it is unreachable. */
    public String runtimeVersion() {
        return provider.version().orElse(null);
    }

    public ModelDetailView show(String name) {
        LlmProvider.Detail detail = provider.show(name);
        return new ModelDetailView(
                name,
                detail.family(),
                detail.format(),
                detail.parameterSize(),
                detail.quantization(),
                detail.capabilities(),
                detail.system(),
                detail.parameters(),
                detail.modelInfo());
    }

    /** Share of the model's weights that the runtime reports as resident in VRAM. */
    private static double gpuPercent(long sizeBytes, long vramBytes) {
        if (sizeBytes <= 0) {
            return 0;
        }
        return Math.round((double) vramBytes / sizeBytes * 1000) / 10.0;
    }
}
