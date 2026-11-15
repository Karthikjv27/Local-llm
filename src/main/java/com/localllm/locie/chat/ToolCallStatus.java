package com.localllm.locie.chat;

/** Lifecycle of a command the model proposed. A denied proposal is still recorded. */
public enum ToolCallStatus {
    PENDING,
    APPROVED,
    DENIED,
    FAILED
}
