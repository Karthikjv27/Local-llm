package com.localllm.locie.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatDetail(
        UUID id,
        String title,
        String model,
        String systemPrompt,
        boolean pinned,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        ContextView context,
        List<MessageView> messages) {
}
