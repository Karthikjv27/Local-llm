package com.localllm.locie.chat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MessageView(
        UUID id,
        String role,
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer latencyMs,
        BigDecimal tokensPerSec,
        String error,
        boolean cancelled,
        Instant createdAt) {

    static MessageView from(Message message) {
        return new MessageView(
                message.getId(),
                message.getRole() == null ? null : message.getRole().name(),
                message.getContent(),
                message.getModel(),
                message.getPromptTokens(),
                message.getCompletionTokens(),
                message.getLatencyMs(),
                message.getTokensPerSec(),
                message.getError(),
                message.isCancelled(),
                message.getCreatedAt());
    }
}
