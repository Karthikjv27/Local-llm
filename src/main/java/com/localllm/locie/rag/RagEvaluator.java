package com.localllm.locie.rag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Grades a retrieval function against a golden set, without touching Spring, Ollama or a database.
 *
 * <p>Two numbers come out of it. <strong>Recall@k</strong> is the share of questions whose expected
 * file appeared in the retrieved list. <strong>MRR</strong> (mean reciprocal rank) averages
 * {@code 1 / rank}, so it also rewards moving the right file from fifth to first - a change recall
 * alone cannot see. Both are measured over distinct files, because one file contributing two
 * passages must not push the next file down a place.
 */
public final class RagEvaluator {

    /** Decimal places kept in the report, matching the scores retrieval reports. */
    private static final int SCALE = 3;

    private RagEvaluator() {
    }

    /**
     * Runs every question through {@code retrieve} and grades the result.
     *
     * @param k        how many files retrieval was allowed to return per question
     * @param cases    the golden set
     * @param retrieve the function under test - normally {@code rag.search(question, k)}
     */
    public static RagEvalReport evaluate(int k, List<RagEvalCase> cases,
                                         Function<String, List<RagSnippet>> retrieve) {
        List<RagEvalCaseResult> results = new ArrayList<>(cases.size());
        int hits = 0;
        double reciprocalRanks = 0;

        for (RagEvalCase evalCase : cases) {
            Found found = find(retrieve.apply(evalCase.question()), evalCase.expected(), k);
            if (found.rank() > 0) {
                hits++;
                reciprocalRanks += 1.0 / found.rank();
            }
            results.add(new RagEvalCaseResult(
                    evalCase.question(), evalCase.expected(), found.rank(), found.path()));
        }

        int total = cases.size();
        return new RagEvalReport(
                k,
                total,
                hits,
                round(total == 0 ? 0 : (double) hits / total),
                round(total == 0 ? 0 : reciprocalRanks / total),
                List.copyOf(results));
    }

    /**
     * The position of the expected file among the retrieved files, or {@link Found#NONE}. Positions
     * are counted over distinct files in retrieval order, so repeated passages from one file count
     * once.
     */
    private static Found find(List<RagSnippet> snippets, String expected, int k) {
        if (snippets == null || snippets.isEmpty()) {
            return Found.NONE;
        }
        Set<String> seen = new HashSet<>();
        int rank = 0;
        for (RagSnippet snippet : snippets) {
            if (snippet == null || !seen.add(snippet.path())) {
                continue;
            }
            rank++;
            if (rank > k) {
                break;
            }
            if (matches(snippet.path(), expected)) {
                return new Found(rank, snippet.path());
            }
        }
        return Found.NONE;
    }

    /**
     * Whether an indexed path is the file a fixture names. The fixture may hold a full path, a path
     * suffix or a bare file name, and either separator works, so a golden set written on one machine
     * still applies on another. A name is matched as a whole path segment, so {@code Chunker.java}
     * does not match {@code RagChunker.java}.
     */
    static boolean matches(String indexedPath, String expected) {
        if (indexedPath == null || expected == null || expected.isBlank()) {
            return false;
        }
        String path = indexedPath.replace('\\', '/');
        String wanted = expected.strip().replace('\\', '/');
        while (wanted.startsWith("./")) {
            wanted = wanted.substring(2);
        }
        wanted = wanted.replaceAll("^/+", "");
        return !wanted.isEmpty() && (path.equals(wanted) || path.endsWith("/" + wanted));
    }

    private static double round(double value) {
        double factor = Math.pow(10, SCALE);
        return Math.round(value * factor) / factor;
    }

    /** Where the expected file ended up, or rank 0 when it was not retrieved. */
    private record Found(int rank, String path) {

        private static final Found NONE = new Found(0, null);
    }
}
