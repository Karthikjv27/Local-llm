package com.localllm.locie.chat;

public record UpdateChatRequest(String title, String model, String systemPrompt, Boolean pinned) {
}
