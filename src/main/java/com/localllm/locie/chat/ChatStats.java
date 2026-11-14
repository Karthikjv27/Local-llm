package com.localllm.locie.chat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate numbers for one conversation: how much was generated, how fast, and how often it failed
 * or was stopped. Counted from the stored messages, so it always matches what the transcript shows.
 */
public record ChatStats(
        UUID chatId,
        int messages,
        int userMessages,
        int assistantMessages,
        int promptTokens,
        int completionTokens,
        BigDecimal averageTokensPerSec,
        Integer averageLatencyMs,
        int failedReplies,
        int stoppedReplies,
        Instant firstMessageAt,
        Instant lastMessageAt) {

    public static ChatStats from(UUID chatId, List<Message> history) {
        int user = 0;
        int assistant = 0;
        int prompt = 0;
        int completion = 0;
        int failed = 0;
        int stopped = 0;
        long latencyTotal = 0;
        int latencySamples = 0;
        BigDecimal rateTotal = BigDecimal.ZERO;
        int rateSamples = 0;
        Instant first = null;
        Instant last = null;

        for (Message message : history) {
            if (message.getRole() == MessageRole.USER) {
                user++;
            } else if (message.getRole() == MessageRole.ASSISTANT) {
                assistant++;
            }
            if (message.getPromptTokens() != null) {
                prompt += message.getPromptTokens();
            }
            if (message.getCompletionTokens() != null) {
                completion += message.getCompletionTokens();
            }
            if (message.getTokensPerSec() != null) {
                rateTotal = rateTotal.add(message.getTokensPerSec());
                rateSamples++;
            }
            if (message.getLatencyMs() != null) {
                latencyTotal += message.getLatencyMs();
                latencySamples++;
            }
            if (message.getError() != null) {
                failed++;
            }
            if (message.isCancelled()) {
                stopped++;
            }
            if (message.getCreatedAt() != null) {
                first = first == null || message.getCreatedAt().isBefore(first) ? message.getCreatedAt() : first;
                last = last == null || message.getCreatedAt().isAfter(last) ? message.getCreatedAt() : last;
            }
        }

        BigDecimal averageRate = rateSamples == 0
                ? null
                : rateTotal.divide(BigDecimal.valueOf(rateSamples), 2, RoundingMode.HALF_UP);
        Integer averageLatency = latencySamples == 0 ? null : (int) (latencyTotal / latencySamples);

        return new ChatStats(chatId, history.size(), user, assistant, prompt, completion,
                averageRate, averageLatency, failed, stopped, first, last);
    }
}
