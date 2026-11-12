package com.localllm.locie.system;

import com.localllm.locie.system.gpu.GpuSnapshot;
import java.time.Instant;
import java.util.List;

public record MetricsResponse(
        Instant timestamp,
        Cpu cpu,
        Memory memory,
        GpuSnapshot gpu,
        OllamaRuntime ollama) {

    public record Cpu(
            String name,
            int physicalCores,
            int logicalProcessors,
            double loadPercent,
            List<Double> perCorePercent) {
    }

    public record Memory(
            long totalBytes,
            long usedBytes,
            long availableBytes,
            double usedPercent) {
    }

    public record OllamaRuntime(
            boolean reachable,
            String version,
            int loadedModels,
            long vramBytes,
            List<String> models) {
    }
}
