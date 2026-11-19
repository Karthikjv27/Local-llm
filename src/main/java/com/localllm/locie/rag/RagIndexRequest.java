package com.localllm.locie.rag;

import jakarta.validation.constraints.NotBlank;

/** The folder to index; absolute or relative to the backend's working directory. */
public record RagIndexRequest(@NotBlank String path) {
}
