package com.localllm.locie.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActiveStreamsTest {

    private final ActiveStreams streams = new ActiveStreams();

    @Test
    void stopFlipsTheFlagForTheRegisteredStream() {
        UUID chatId = UUID.randomUUID();
        ActiveStreams.Registration registration = streams.register(chatId);

        assertThat(streams.stop(chatId)).isTrue();
        assertThat(registration.stopped()).isTrue();
    }

    @Test
    void stopWithoutAStreamReportsFalse() {
        assertThat(streams.stop(UUID.randomUUID())).isFalse();
    }

    @Test
    void closingUnregistersTheChat() {
        UUID chatId = UUID.randomUUID();
        ActiveStreams.Registration registration = streams.register(chatId);

        registration.close();

        assertThat(streams.isActive(chatId)).isFalse();
        assertThat(streams.stop(chatId)).isFalse();
    }

    @Test
    void closingAnOldStreamCannotRemoveANewerOne() {
        UUID chatId = UUID.randomUUID();
        ActiveStreams.Registration first = streams.register(chatId);
        ActiveStreams.Registration second = streams.register(chatId);

        first.close();

        assertThat(streams.isActive(chatId)).isTrue();
        assertThat(streams.stop(chatId)).isTrue();
        assertThat(second.stopped()).isTrue();
    }
}
