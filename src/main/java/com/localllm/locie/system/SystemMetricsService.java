package com.localllm.locie.system;

import com.localllm.locie.common.OllamaUnavailableException;
import com.localllm.locie.model.ModelCatalogService;
import com.localllm.locie.model.RunningModelView;
import com.localllm.locie.system.gpu.GpuService;
import java.time.Instant;
import java.util.List;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import org.springframework.stereotype.Service;

/** Host CPU/RAM (OSHI), GPU (pluggable probes) and Ollama residency, sampled on request. */
@Service
public class SystemMetricsService {

    private final HardwareAbstractionLayer hardware = new SystemInfo().getHardware();
    private final CentralProcessor processor = hardware.getProcessor();
    private final GlobalMemory memory = hardware.getMemory();
    private final GpuService gpuService;
    private final ModelCatalogService catalog;

    private final Object tickLock = new Object();
    private long[] previousSystemTicks;
    private long[][] previousCoreTicks;

    public SystemMetricsService(GpuService gpuService, ModelCatalogService catalog) {
        this.gpuService = gpuService;
        this.catalog = catalog;
        this.previousSystemTicks = processor.getSystemCpuLoadTicks();
        this.previousCoreTicks = processor.getProcessorCpuLoadTicks();
    }

    public MetricsResponse snapshot() {
        return new MetricsResponse(Instant.now(), cpu(), memory(), gpuService.current(), ollama());
    }

    private MetricsResponse.Cpu cpu() {
        long[] systemTicks;
        long[][] coreTicks;
        synchronized (tickLock) {
            systemTicks = processor.getSystemCpuLoadTicks();
            coreTicks = processor.getProcessorCpuLoadTicks();
        }

        double load;
        List<Double> perCore;
        synchronized (tickLock) {
            load = loadPercent(previousSystemTicks, systemTicks);
            perCore = perCorePercent(previousCoreTicks, coreTicks);
            previousSystemTicks = systemTicks;
            previousCoreTicks = coreTicks;
        }

        CentralProcessor.ProcessorIdentifier identifier = processor.getProcessorIdentifier();
        return new MetricsResponse.Cpu(
                identifier.getName() == null ? "unknown" : identifier.getName().trim(),
                processor.getPhysicalProcessorCount(),
                processor.getLogicalProcessorCount(),
                load,
                perCore);
    }

    private MetricsResponse.Memory memory() {
        long total = memory.getTotal();
        long available = memory.getAvailable();
        long used = Math.max(0, total - available);
        double percent = total == 0 ? 0 : Math.round((double) used / total * 1000) / 10.0;
        return new MetricsResponse.Memory(total, used, available, percent);
    }

    private MetricsResponse.OllamaRuntime ollama() {
        try {
            List<RunningModelView> running = catalog.running();
            long vram = running.stream().mapToLong(RunningModelView::vramBytes).sum();
            return new MetricsResponse.OllamaRuntime(
                    true,
                    catalog.runtimeVersion(),
                    running.size(),
                    vram,
                    running.stream().map(RunningModelView::name).toList());
        } catch (OllamaUnavailableException ex) {
            return new MetricsResponse.OllamaRuntime(false, null, 0, 0, List.of());
        }
    }

    private static double loadPercent(long[] previous, long[] current) {
        if (previous == null || current == null || previous.length != current.length) {
            return 0;
        }
        long total = 0;
        long idle = 0;
        int idleIndex = CentralProcessor.TickType.IDLE.getIndex();
        for (int i = 0; i < current.length; i++) {
            long delta = Math.max(0, current[i] - previous[i]);
            total += delta;
            if (i == idleIndex) {
                idle += delta;
            }
        }
        if (total == 0) {
            return 0;
        }
        return Math.round((1.0 - (double) idle / total) * 1000) / 10.0;
    }

    private static List<Double> perCorePercent(long[][] previous, long[][] current) {
        if (previous == null || current == null || previous.length != current.length) {
            return List.of();
        }
        java.util.List<Double> values = new java.util.ArrayList<>(current.length);
        for (int core = 0; core < current.length; core++) {
            values.add(loadPercent(previous[core], current[core]));
        }
        return List.copyOf(values);
    }
}
