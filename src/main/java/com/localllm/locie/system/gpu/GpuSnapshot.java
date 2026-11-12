package com.localllm.locie.system.gpu;

import java.util.List;

/**
 * A point-in-time GPU reading. {@code utilizationPercent} and memory fields are null when the
 * platform exposes the adapter but not its counters, and {@code available} is false when no
 * probing method worked at all.
 */
public record GpuSnapshot(
        String source,
        boolean available,
        List<GpuAdapter> adapters,
        Double utilizationPercent,
        Long memoryUsedBytes,
        Long memoryTotalBytes,
        Long temperatureC,
        String detail) {

    public static GpuSnapshot unavailable(String detail) {
        return new GpuSnapshot("unavailable", false, List.of(), null, null, null, null, detail);
    }
}
