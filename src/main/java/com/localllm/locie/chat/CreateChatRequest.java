package com.localllm.locie.chat;

import jakarta.validation.constraints.NotBlank;

public record CreateChatRequest(String title, @NotBlank String model, String systemPrompt) {
}
