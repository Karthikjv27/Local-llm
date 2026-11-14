package com.localllm.locie.chat;

import java.time.Instant;
import java.util.UUID;

public record ChatSummary(
        UUID id,
        String title,
        String model,
        boolean pinned,
        boolean archived,
        Instant createdAt,
        Instant updatedAt) {

    static ChatSummary from(Chat chat) {
        return new ChatSummary(
                chat.getId(),
                chat.getTitle(),
                chat.getModel(),
                chat.isPinned(),
                chat.isArchived(),
                chat.getCreatedAt(),
                chat.getUpdatedAt());
    }
}
