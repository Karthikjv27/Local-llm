package com.localllm.locie.rag;

import java.util.List;

/**
 * The result of one evaluation run: how many questions found their file in the first {@code k}
 * (recall@k), how early on average (MRR), and the rank of every question.
 *
 * <p>Both metrics are rounded to three decimals so a printed report is stable across runs.
 */
public record RagEvalReport(
        int k,
        int total,
        int hits,
        double recallAtK,
        double mrr,
        List<RagEvalCaseResult> cases) {
}
