package com.localllm.locie.system.gpu;

import java.util.Optional;

/** A vendor-specific way of reading GPU load. Implementations must never throw. */
public interface GpuProbe {

    Optional<GpuSnapshot> snapshot();
}
