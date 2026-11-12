package com.localllm.locie.common;

public class OllamaModelNotFoundException extends RuntimeException {

    public OllamaModelNotFoundException(String message) {
        super(message);
    }

    public OllamaModelNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
