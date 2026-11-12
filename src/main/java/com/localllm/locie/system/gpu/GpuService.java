package com.localllm.locie.system.gpu;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Picks the first probe that has something to report and caches the result briefly, so the UI can
 * poll faster than the underlying tools can be spawned.
 */
@Component
public class GpuService {

    private static final Duration TTL = Duration.ofSeconds(2);

    private final List<GpuProbe> probes;

    private volatile GpuSnapshot cached;
    private volatile long cachedAt;

    public GpuService(List<GpuProbe> probes) {
        this.probes = probes;
    }

    public GpuSnapshot current() {
        long now = System.currentTimeMillis();
        GpuSnapshot snapshot = cached;
        if (snapshot != null && now - cachedAt < TTL.toMillis()) {
            return snapshot;
        }
        GpuSnapshot fresh = probe();
        cached = fresh;
        cachedAt = now;
        return fresh;
    }

    private GpuSnapshot probe() {
        for (GpuProbe probe : probes) {
            try {
                Optional<GpuSnapshot> snapshot = probe.snapshot();
                if (snapshot.isPresent()) {
                    return snapshot.get();
                }
            } catch (RuntimeException ignored) {
                // a broken probe must not break the metrics endpoint
            }
        }
        return GpuSnapshot.unavailable("No supported GPU probing method found on this machine");
    }
}
