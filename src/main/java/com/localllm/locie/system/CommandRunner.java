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
 * output cap. There is no shell in the middle, so metacharacters in arguments have no effect.
 */
public final class CommandRunner {

    private CommandRunner() {
    }

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    public static Result run(List<String> argv, Duration timeout, int maxBytes) {
        Process process;
        try {
            process = new ProcessBuilder(argv).start();
        } catch (IOException ex) {
            String reason = ex.getMessage() == null ? "could not start the command" : ex.getMessage();
            return new Result(-1, "", reason, false);
        }

        AtomicReference<String> stdout = new AtomicReference<>("");
        AtomicReference<String> stderr = new AtomicReference<>("");
        Thread outReader = Thread.ofVirtual().start(() -> stdout.set(read(process.getInputStream(), maxBytes)));
        Thread errReader = Thread.ofVirtual().start(() -> stderr.set(read(process.getErrorStream(), maxBytes)));

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
        joinQuietly(outReader);
        joinQuietly(errReader);

        int exitCode = -1;
        if (finished) {
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                // died between waitFor and exitValue
            }
        }
        return new Result(exitCode, stdout.get(), stderr.get(), !finished);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String read(InputStream stream, int maxBytes) {
        StringBuilder text = new StringBuilder();
        try (stream) {
            byte[] chunk = new byte[4096];
            int total = 0;
            int read;
            while ((read = stream.read(chunk)) != -1) {
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
