package com.localllm.locie.rag;

/**
 * One golden-set entry: a question, and the file retrieval should return for it. The expected value
 * may be an absolute path, a path suffix or just a file name, so the same fixture works on another
 * machine and another checkout.
 */
public record RagEvalCase(String question, String expected) {
}
