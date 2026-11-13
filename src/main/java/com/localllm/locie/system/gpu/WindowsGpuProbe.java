package com.localllm.locie.system.gpu;

import com.localllm.locie.system.CommandRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Vendor-neutral Windows probe (covers AMD and Intel, where nvidia-smi does not exist).
 *
 * <p>Adapter names and VRAM come from {@code Win32_VideoController}; live load comes from the GPU
 * performance counters. Those counters are per engine and per adapter LUID, so this reports the
 * busiest engine as utilization and the sum of dedicated usage as memory in use.
 */
@Component
@Order(20)
public class WindowsGpuProbe implements GpuProbe {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    // a cold PowerShell start on a busy machine can exceed three seconds
    private static final Duration TIMEOUT = Duration.ofSeconds(6);
    private static final long ADAPTER_TTL_MILLIS = 120_000L;

    private static final String ADAPTER_SCRIPT = """
            [Console]::OutputEncoding = [Text.Encoding]::UTF8;
            Get-CimInstance -ClassName Win32_VideoController -ErrorAction SilentlyContinue |
              ForEach-Object { '{0}|{1}' -f $_.Name, $_.AdapterRAM }""";

    private static final String PERF_SCRIPT = """
            [Console]::OutputEncoding = [Text.Encoding]::UTF8;
            $e = Get-CimInstance -ClassName Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -ErrorAction SilentlyContinue |
                 Measure-Object -Property UtilizationPercentage -Maximum;
            $m = Get-CimInstance -ClassName Win32_PerfFormattedData_GPUPerformanceCounters_GPUAdapterMemory -ErrorAction SilentlyContinue |
                 Measure-Object -Property DedicatedUsage -Sum;
            [String]::Format([Globalization.CultureInfo]::InvariantCulture, 'UTIL={0};MEM={1}', $e.Maximum, $m.Sum)""";

    private volatile List<GpuAdapter> cachedAdapters = List.of();
    private volatile long adaptersLoadedAt;

    @Override
    public Optional<GpuSnapshot> snapshot() {
        if (!WINDOWS) {
            return Optional.empty();
        }
        List<GpuAdapter> adapters = adapters();
        if (adapters.isEmpty()) {
            return Optional.empty();
        }

        Perf perf = perf();
        long maxAdapterMemory = adapters.stream()
                .map(GpuAdapter::memoryTotalBytes)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);

        String detail = perf.utilizationPercent() == null
                ? "GPU adapter detected; performance counters unavailable"
                : "GPU engine counters (Windows), busiest engine reported";

        return Optional.of(new GpuSnapshot(
                "windows-gpu-counters",
                true,
                adapters,
                perf.utilizationPercent(),
                perf.memoryUsedBytes(),
                maxAdapterMemory == 0 ? null : maxAdapterMemory,
                null,
                detail));
    }

    private List<GpuAdapter> adapters() {
        long now = System.currentTimeMillis();
        if (!cachedAdapters.isEmpty() && now - adaptersLoadedAt < ADAPTER_TTL_MILLIS) {
            return cachedAdapters;
        }
        CommandRunner.Result result = CommandRunner.run(powershell(ADAPTER_SCRIPT), TIMEOUT, 16_384);
        if (!result.ok()) {
            return cachedAdapters;
        }
        List<GpuAdapter> adapters = new ArrayList<>();
        for (String line : result.stdout().split("\\R")) {
            int separator = line.lastIndexOf('|');
            if (separator <= 0) {
                continue;
            }
            String name = line.substring(0, separator).trim();
            if (name.isEmpty()) {
                continue;
            }
            adapters.add(new GpuAdapter(name, parseLong(line.substring(separator + 1))));
        }
        if (!adapters.isEmpty()) {
            cachedAdapters = List.copyOf(adapters);
            adaptersLoadedAt = now;
        }
        return cachedAdapters;
    }

    private Perf perf() {
        CommandRunner.Result result = CommandRunner.run(powershell(PERF_SCRIPT), TIMEOUT, 4_096);
        if (!result.ok()) {
            return new Perf(null, null);
        }
        Double utilization = null;
        Long memoryUsed = null;
        for (String part : result.stdout().trim().split(";")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = part.substring(0, separator).trim();
            String value = part.substring(separator + 1).trim();
            if ("UTIL".equalsIgnoreCase(key)) {
                utilization = parseDouble(value);
            } else if ("MEM".equalsIgnoreCase(key)) {
                Long parsed = parseLong(value);
                memoryUsed = parsed == null || parsed == 0 ? null : parsed;
            }
        }
        return new Perf(utilization, memoryUsed);
    }

    private static List<String> powershell(String script) {
        return List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                script.replaceAll("\\s+", " ").trim());
    }

    private static Double parseDouble(String raw) {
        String value = raw.trim().replace(',', '.');
        if (value.isEmpty() || "0".equals(value)) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long parseLong(String raw) {
        String value = raw.trim();
        if (value.isEmpty() || "-".equals(value) || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private record Perf(Double utilizationPercent, Long memoryUsedBytes) {
    }
}
