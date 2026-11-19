package com.localllm.locie.rag;

import java.util.List;

/**
 * Turns text into vectors. A port rather than a direct Ollama call, so indexing and ranking can be
 * tested without a running model.
 */
public interface Embedder {

    /** One vector per input, in the same order. */
    List<float[]> embed(List<String> texts);

    /** The embedding model in use, for error messages and the UI. */
    String model();
}
