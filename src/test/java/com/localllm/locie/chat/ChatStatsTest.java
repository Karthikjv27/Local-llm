package com.localllm.locie.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatStatsTest {

    private static final UUID CHAT_ID = UUID.randomUUID();

    @Test
    void emptyHistoryReportsZerosAndNoAverages() {
        ChatStats stats = ChatStats.from(CHAT_ID, List.of());

        assertThat(stats.messages()).isZero();
        assertThat(stats.averageTokensPerSec()).isNull();
        assertThat(stats.averageLatencyMs()).isNull();
        assertThat(stats.firstMessageAt()).isNull();
    }

    @Test
    void averagesRatesAndLatenciesAcrossReplies() {
        Message first = assistant(1, 100, "120", "10.00", null, false);
        Message second = assistant(2, 300, "80", "20.00", null, false);

        ChatStats stats = ChatStats.from(CHAT_ID, List.of(assistantUser(), first, second));

        assertThat(stats.messages()).isEqualTo(3);
        assertThat(stats.userMessages()).isEqualTo(1);
        assertThat(stats.assistantMessages()).isEqualTo(2);
        assertThat(stats.completionTokens()).isEqualTo(200);
        assertThat(stats.averageTokensPerSec()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(stats.averageLatencyMs()).isEqualTo(200);
    }

    @Test
    void countsFailuresAndStopsSeparately() {
        Message failed = assistant(1, 50, "0", null, "Ollama is unreachable", false);
        Message stopped = assistant(2, 50, "40", "9.00", null, true);

        ChatStats stats = ChatStats.from(CHAT_ID, List.of(assistantUser(), failed, stopped));

        assertThat(stats.failedReplies()).isEqualTo(1);
        assertThat(stats.stoppedReplies()).isEqualTo(1);
        assertThat(stats.averageTokensPerSec()).isEqualByComparingTo(new BigDecimal("9.00"));
    }

    @Test
    void tracksFirstAndLastMessageTimes() {
        Message early = assistant(1, 10, "5", null, null, false);
        early.setCreatedAt(Instant.parse("2026-01-01T10:00:00Z"));
        Message late = assistant(2, 10, "5", null, null, false);
        late.setCreatedAt(Instant.parse("2026-01-02T10:00:00Z"));

        ChatStats stats = ChatStats.from(CHAT_ID, List.of(early, late));

        assertThat(stats.firstMessageAt()).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
        assertThat(stats.lastMessageAt()).isEqualTo(Instant.parse("2026-01-02T10:00:00Z"));
    }

    private static Message assistantUser() {
        Message message = new Message();
        message.setRole(MessageRole.USER);
        message.setContent("hi");
        return message;
    }

    private static Message assistant(int seq, int latencyMs, String completionTokens, String rate,
                                     String error, boolean cancelled) {
        Message message = new Message();
        message.setChat(null);
        message.setSeq(seq);
        message.setRole(MessageRole.ASSISTANT);
        message.setContent("reply");
        message.setLatencyMs(latencyMs);
        message.setCompletionTokens(completionTokens == null ? null : Integer.parseInt(completionTokens));
        message.setTokensPerSec(rate == null ? null : new BigDecimal(rate));
        message.setError(error);
        message.setCancelled(cancelled);
        return message;
    }
}
