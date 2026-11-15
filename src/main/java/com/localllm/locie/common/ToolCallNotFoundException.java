package com.localllm.locie.common;

public class ToolCallNotFoundException extends RuntimeException {

    public ToolCallNotFoundException(String message) {
        super(message);
    }
}
