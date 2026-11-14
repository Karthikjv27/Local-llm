package com.localllm.locie.system;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Runs an external program with an argument list (never a shell string), a hard timeout and an
 * output cap. There is no shell in the middle, so metacharacters in arguments have no effect.
 *
 * <p>Output can be observed while the process runs: {@code ollama pull} redraws a progress bar with
 * carriage returns, so lines are split on {@code \r} as well as {@code \n} to surface each update.
 */
public final class CommandRunner {

    /** CSI/OSC terminal control sequences and the braille spinner frames Ollama draws. */
    private static final Pattern ANSI = Pattern.compile(
            "\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\u001B[@-_]");
    private static final Pattern SPINNER = Pattern.compile("[\u2800-\u28FF]");

    private CommandRunner() {
    }

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    public static Result run(List<String> argv, Duration timeout, int maxBytes) {
        return run(argv, timeout, maxBytes, line -> { });
    }

    /** Runs {@code argv}, calling {@code onLine} for every output line as it is produced. */
    public static Result run(List<String> argv, Duration timeout, int maxBytes, Consumer<String> onLine) {
        Process process;
        try {
            process = new ProcessBuilder(argv).start();
        } catch (IOException ex) {
            String reason = ex.getMessage() == null ? "could not start the command" : ex.getMessage();
            return new Result(-1, "", reason, false);
        }

        AtomicReference<String> stdout = new AtomicReference<>("");
        AtomicReference<String> stderr = new AtomicReference<>("");
        Thread outReader = Thread.ofVirtual()
                .start(() -> stdout.set(read(process.getInputStream(), maxBytes, onLine)));
        Thread errReader = Thread.ofVirtual()
                .start(() -> stderr.set(read(process.getErrorStream(), maxBytes, onLine)));

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            killTree(process);
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

    /**
     * Kills children before the parent. A bare destroy() leaves helpers behind - `ollama pull` and
     * the PowerShell probes both spawn child processes that would otherwise outlive the timeout.
     */
    private static void killTree(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (RuntimeException ignored) {
                // the child may have exited between listing and killing
            }
        });
        process.destroyForcibly();
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Keeps the full text up to {@code maxBytes} and reports each completed line to {@code onLine}.
     * Terminal control codes are consumed rather than copied: a cursor reset ({@code ESC[nG}), which
     * is how Ollama redraws its progress bar, ends the current line just like a carriage return.
     */
    static String read(InputStream stream, int maxBytes, Consumer<String> onLine) {
        StringBuilder text = new StringBuilder();
        StringBuilder line = new StringBuilder();
        StringBuilder escape = new StringBuilder();
        boolean escaping = false;

        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] chunk = new char[2048];
            int read;
            while ((read = reader.read(chunk)) != -1) {
                for (int i = 0; i < read; i++) {
                    char character = chunk[i];

                    if (escaping) {
                        escape.append(character);
                        char kind = escape.charAt(1);
                        boolean complete;
                        if (kind == '[') {
                            // the '[' itself is not a terminator: wait for the final byte of the CSI
                            complete = escape.length() >= 3 && character >= '@' && character <= '~';
                        } else if (kind == ']') {
                            complete = character == '\u0007'
                                    || (character == '\\' && escape.length() > 2
                                            && escape.charAt(escape.length() - 2) == '\u001B');
                        } else {
                            complete = true;
                        }

                        if (complete) {
                            boolean cursorReset = kind == '[' && character == 'G';
                            escaping = false;
                            escape.setLength(0);
                            if (cursorReset) {
                                breakLine(text, line, maxBytes, onLine);
                            }
                        }
                        continue;
                    }

                    if (character == '\u001B') {
                        escaping = true;
                        escape.setLength(0);
                        escape.append(character);
                    } else if (character == '\n' || character == '\r') {
                        breakLine(text, line, maxBytes, onLine);
                    } else {
                        if (line.length() < 4096) {
                            line.append(character);
                        }
                        if (text.length() < maxBytes) {
                            text.append(character);
                        }
                    }
                }
            }
            if (line.length() > 0 && text.length() < maxBytes) {
                String cleaned = sanitize(line.toString());
                if (!cleaned.isBlank()) {
                    onLine.accept(cleaned);
                }
            }
        } catch (IOException ignored) {
            // stream closed early because the process was killed on timeout
        }
        return sanitize(text.toString());
    }

    private static void breakLine(StringBuilder text, StringBuilder line, int maxBytes,
                                  Consumer<String> onLine) {
        if (line.length() > 0) {
            String cleaned = sanitize(line.toString());
            if (!cleaned.isBlank() && text.length() < maxBytes) {
                onLine.accept(cleaned);
            }
            line.setLength(0);
        }
        if (text.length() < maxBytes) {
            text.append('\n');
        }
    }

    /** Drops any control codes that survived, so output reads the same in a browser as in a shell. */
    private static String sanitize(String text) {
        String withoutAnsi = ANSI.matcher(text).replaceAll("");
        return SPINNER.matcher(withoutAnsi).replaceAll("").stripTrailing();
    }
}
