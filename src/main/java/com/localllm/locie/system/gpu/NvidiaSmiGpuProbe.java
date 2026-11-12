package com.localllm.locie.system.gpu;

import com.localllm.locie.system.CommandRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * NVIDIA GPUs via nvidia-smi. Reports empty when the tool is absent, and remembers that for a
 * minute so a machine without the driver does not spawn a missing process on every poll.
 */
@Component
@Order(10)
public class NvidiaSmiGpuProbe implements GpuProbe {

    private static final long BYTES_PER_MIB = 1024L * 1024L;
    private static final List<String> QUERY = List.of(
            "nvidia-smi",
            "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu",
            "--format=csv,noheader,nounits");
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final long SKIP_MILLIS = 60_000L;

    private volatile long skipUntil;

    @Override
    public Optional<GpuSnapshot> snapshot() {
        if (System.currentTimeMillis() < skipUntil) {
            return Optional.empty();
        }
        CommandRunner.Result result = CommandRunner.run(QUERY, TIMEOUT, 16_384);
        if (!result.ok() || result.output().isBlank()) {
            skipUntil = System.currentTimeMillis() + SKIP_MILLIS;
            return Optional.empty();
        }

        List<GpuAdapter> adapters = new ArrayList<>();
        double maxUtilization = 0;
        long usedBytes = 0;
        long totalBytes = 0;
        Long maxTemp = null;

        for (String line : result.output().split("\\R")) {
            String[] parts = line.split(",");
            if (parts.length < 5) {
                continue;
            }
            String name = parts[0].trim();
            Long memoryTotal = parseLong(parts[3]);
            adapters.add(new GpuAdapter(name, memoryTotal == null ? null : memoryTotal * BYTES_PER_MIB));

            Double utilization = parseDouble(parts[1]);
            if (utilization != null) {
                maxUtilization = Math.max(maxUtilization, utilization);
            }
            Long used = parseLong(parts[2]);
            if (used != null) {
                usedBytes += used * BYTES_PER_MIB;
            }
            if (memoryTotal != null) {
                totalBytes += memoryTotal * BYTES_PER_MIB;
            }
            Long temp = parseLong(parts[4]);
            if (temp != null) {
                maxTemp = maxTemp == null ? temp : Math.max(maxTemp, temp);
            }
        }

        if (adapters.isEmpty()) {
            skipUntil = System.currentTimeMillis() + SKIP_MILLIS;
            return Optional.empty();
        }

        return Optional.of(new GpuSnapshot(
                "nvidia-smi",
                true,
                List.copyOf(adapters),
                maxUtilization,
                usedBytes == 0 ? null : usedBytes,
                totalBytes == 0 ? null : totalBytes,
                maxTemp,
                "NVIDIA GPU via nvidia-smi"));
    }

    private static Double parseDouble(String raw) {
        String value = raw.trim();
        if (value.isEmpty() || value.startsWith("[")) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long parseLong(String raw) {
        String value = raw.trim();
        if (value.isEmpty() || value.startsWith("[")) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
