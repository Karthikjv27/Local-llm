package com.localllm.locie.system;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs an external program with an argument list (never a shell string), a hard timeout and an
 * output cap. Used by the hardware probes, which must never block the request thread.
 */
public final class CommandRunner {

    private CommandRunner() {
    }

    public record Result(int exitCode, String output, boolean timedOut) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    public static Result run(List<String> argv, Duration timeout, int maxBytes) {
        Process process;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (IOException ex) {
            return new Result(-1, "", false);
        }

        AtomicReference<String> output = new AtomicReference<>("");
        Thread reader = Thread.ofVirtual().start(() -> output.set(read(process, maxBytes)));

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            process.destroyForcibly();
        }
        try {
            reader.join(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        int exitCode = -1;
        if (finished) {
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                // process died between waitFor and exitValue
            }
        }
        return new Result(exitCode, output.get(), !finished);
    }

    private static String read(Process process, int maxBytes) {
        StringBuilder text = new StringBuilder();
        try (InputStream in = process.getInputStream()) {
            byte[] chunk = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (total >= maxBytes) {
                    continue;
                }
                int length = Math.min(read, maxBytes - total);
                text.append(new String(chunk, 0, length, StandardCharsets.UTF_8));
                total += length;
            }
        } catch (IOException ignored) {
            // stream closed early because the process was killed on timeout
        }
        return text.toString();
    }
}
