package com.localllm.locie.chat;

/** A downloadable transcript, already rendered as Markdown. */
public record ChatExport(String filename, String markdown) {
}
