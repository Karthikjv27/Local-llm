package com.localllm.locie.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the framing rules in {@link CommandRunner#read}: these arrive as raw terminal output from
 * {@code ollama pull}, which redraws progress with cursor escapes and draws a braille spinner.
 */
class CommandRunnerTest {

    private static final int MAX_BYTES = 256 * 1024;

    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void reset() {
        lines.clear();
    }

    private String read(String output) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        return CommandRunner.read(new ByteArrayInputStream(bytes), MAX_BYTES, lines::add);
    }

    @Test
    void cursorResetEndsTheCurrentLine() {
        // a progress redraw: the bar is replaced by moving the cursor to column 1, not by a CR
        String stdout = read("pulling manifest\u001B[1Gpulling 4a188102020e: 45%\u001B[1Gsuccess\n");

        assertThat(lines).containsExactly(
                "pulling manifest",
                "pulling 4a188102020e: 45%",
                "success");
        assertThat(stdout).doesNotContain("\u001B");
    }

    @Test
    void escapeSequencesAreSwallowed() {
        // the bracketed-paste hints, cursor hide/show, erase-line and title codes Ollama wraps its
        // output in: none of them may reach the caller, and only the cursor reset starts a new line
        String stdout = read(
                "\u001B[?2026h\u001B[?25lpulling manifest \u001B[K\u001B[?25h\u001B[?2026l"
                        + "\u001B]0;ollama\u0007\u001B[1Gdone\n");

        assertThat(lines).containsExactly("pulling manifest", "done");
        assertThat(stdout)
                .doesNotContain("\u001B")
                .doesNotContain("?2026h")
                .doesNotContain("?25l")
                .doesNotContain("[K")
                .contains("pulling manifest")
                .contains("done");
    }

    @Test
    void spinnerFramesAreStripped() {
        String stdout = read("pulling manifest \u28FF\npulling manifest \u28BD\npulling manifest \u28B9\n");

        assertThat(lines).containsExactly("pulling manifest", "pulling manifest", "pulling manifest");
        assertThat(stdout).doesNotContain("\u28FF").doesNotContain("\u28BD").doesNotContain("\u28B9");
    }

    @Test
    void multiByteCharacterSplitAcrossReadsSurvives() {
        String output = "σίγμα 世界 🚀\npulling manifest\n";
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);

        // one byte per read splits every multi-byte sequence at the decoder's input boundary
        String byteWise = CommandRunner.read(chunked(bytes, 1), MAX_BYTES, lines::add);
        assertThat(lines).containsExactly("σίγμα 世界 🚀", "pulling manifest");
        assertThat(byteWise).doesNotContain("\uFFFD").contains("σίγμα 世界 🚀");

        // a three-byte-of-a-four-byte-character split inside a larger read
        lines.clear();
        String chunked = CommandRunner.read(chunked(bytes, 7), MAX_BYTES, lines::add);
        assertThat(lines).containsExactly("σίγμα 世界 🚀", "pulling manifest");
        assertThat(chunked).doesNotContain("\uFFFD").contains("σίγμα 世界 🚀");
    }

    @Test
    void longSingleLineIsNotSplitByTheReadBuffer() {
        String longLine = "x".repeat(3000);
        String stdout = read(longLine + "\n");

        assertThat(lines).containsExactly(longLine);
        assertThat(stdout).contains(longLine);
    }

    /** Hands out at most {@code chunk} bytes per read so decoding must span calls. */
    private static InputStream chunked(byte[] bytes, int chunk) {
        return new InputStream() {
            private int offset;

            @Override
            public int read() {
                return offset < bytes.length ? bytes[offset++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] target, int targetOffset, int length) {
                if (offset >= bytes.length) {
                    return -1;
                }
                int count = Math.min(Math.min(length, chunk), bytes.length - offset);
                System.arraycopy(bytes, offset, target, targetOffset, count);
                offset += count;
                return count;
            }
        };
    }
}
