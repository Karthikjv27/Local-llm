package com.localllm.locie.rag;

import java.util.List;

/**
 * The system message retrieval contributes to a prompt, plus the passages it was built from. The
 * snippets travel with the text so the reply can record what it was shown.
 */
public record RagContext(String text, List<RagSnippet> snippets) {
}
