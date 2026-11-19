package com.localllm.locie.rag;

/**
 * One graded question: the 1-based position of the expected file among the retrieved files, or 0
 * when it was not retrieved at all, plus the path that matched so a surprising result can be read.
 */
public record RagEvalCaseResult(
        String question,
        String expected,
        int rank,
        String path) {
}
