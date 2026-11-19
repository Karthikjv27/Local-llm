package com.localllm.locie.chat;

/** One indexed file a reply drew on: the path, the chunk inside it, and its cosine score. */
public record MessageSource(String path, int ordinal, double score) {
}
