package com.localllm.locie.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContextBudgetTest {

    @Test
    void keepsEverythingWhenTheConversationFits() {
        ContextBudget.Window window = ContextBudget.fit(null, List.of(
                message(MessageRole.USER, "hello"),
                message(MessageRole.ASSISTANT, "hi there")), 4096);

        assertThat(window.included()).isEqualTo(2);
        assertThat(window.trimmed()).isFalse();
        assertThat(window.prompt()).extracting(entry -> entry.get("role"))
                .containsExactly("user", "assistant");
    }

    @Test
    void dropsTheOldestMessagesWhenOverBudget() {
        // 4,000 characters ~ 1,000 tokens, so only the newest prompt fits in 1,200 tokens
        ContextBudget.Window window = ContextBudget.fit(null, List.of(
                message(MessageRole.ASSISTANT, "a".repeat(4_000)),
                message(MessageRole.USER, "b".repeat(4_000))), 1_200);

        assertThat(window.included()).isEqualTo(1);
        assertThat(window.trimmed()).isTrue();
        assertThat(window.prompt()).singleElement()
                .extracting(entry -> entry.get("content")).isEqualTo("b".repeat(4_000));
    }

    @Test
    void aWindowNeverStartsWithAReply() {
        ContextBudget.Window window = ContextBudget.fit(null, List.of(
                message(MessageRole.ASSISTANT, "orphaned answer"),
                message(MessageRole.USER, "question"),
                message(MessageRole.ASSISTANT, "answer")), 4096);

        assertThat(window.prompt().get(0).get("content")).isEqualTo("question");
        assertThat(window.included()).isEqualTo(2);
        assertThat(window.trimmed()).isTrue();
    }

    @Test
    void theSystemPromptCountsTowardTheBudget() {
        ContextBudget.Window window = ContextBudget.fit("s".repeat(1_600), List.of(
                message(MessageRole.USER, "u".repeat(400))), 420);

        assertThat(window.included()).isZero();
        assertThat(window.trimmed()).isTrue();
        assertThat(window.prompt()).singleElement().extracting(entry -> entry.get("role"))
                .isEqualTo("system");
    }

    @Test
    void estimatesFourCharactersPerToken() {
        assertThat(ContextBudget.estimate("")).isZero();
        assertThat(ContextBudget.estimate("one")).isEqualTo(1);
        assertThat(ContextBudget.estimate("abcdefgh")).isEqualTo(2);
    }

    private static Message message(MessageRole role, String content) {
        Message message = new Message();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
