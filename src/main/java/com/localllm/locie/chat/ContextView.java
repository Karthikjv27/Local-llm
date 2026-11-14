package com.localllm.locie.chat;

/** How much of the conversation the next request will send, and what was left out. */
public record ContextView(
        int usedTokens,
        int limitTokens,
        int includedMessages,
        int consideredMessages,
        boolean trimmed) {
}
