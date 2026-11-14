package com.localllm.locie.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Decides how much of a conversation is sent to the model.
 *
 * <p>Ollama silently truncates a prompt that is longer than the model's context window, which loses
 * the beginning of a chat without telling anyone. This keeps the newest turns and reports what was
 * dropped so the UI can show it.
 */
public final class ContextBudget {

    /** Per-message framing: role markers and separators the model still has to read. */
    private static final int MESSAGE_OVERHEAD = 4;

    private ContextBudget() {
    }

    /** Roughly four characters per token, the usual English/code average for these tokenizers. */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }

    /**
     * @param prompt        messages in conversation order, system prompt first
     * @param usedTokens    estimated tokens of {@code prompt}
     * @param limitTokens   the budget that was applied
     * @param included      messages that fit
     * @param considered    messages offered, after the history-limit cut
     * @param trimmed       whether anything was dropped
     */
    public record Window(List<Map<String, String>> prompt, int usedTokens, int limitTokens,
                         int included, int considered, boolean trimmed) {

        public ContextView view() {
            return new ContextView(usedTokens, limitTokens, included, considered, trimmed);
        }
    }

    /**
     * Selects the newest messages that fit in {@code limitTokens}. The system prompt always stays.
     * A window that would start with a reply drops it, because a model answering with no question in
     * front of it produces garbage.
     */
    public static Window fit(String systemPrompt, List<Message> history, int limitTokens) {
        String system = systemPrompt == null || systemPrompt.isBlank() ? null : systemPrompt;
        int used = system == null ? 0 : estimate(system) + MESSAGE_OVERHEAD;

        Deque<Map<String, String>> selected = new ArrayDeque<>();
        int considered = 0;

        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            String text = message.getContent() == null ? "" : message.getContent();
            if (message.getRole() == MessageRole.ASSISTANT && text.isBlank()) {
                continue; // a failed or empty reply is not part of a prompt
            }
            considered++;
            int cost = estimate(text) + MESSAGE_OVERHEAD;
            if (used + cost > limitTokens) {
                break;
            }
            selected.addFirst(Map.of("role", role(message.getRole()), "content", text));
            used += cost;
        }

        while (!selected.isEmpty() && "assistant".equals(selected.peekFirst().get("role"))) {
            Map<String, String> dropped = selected.removeFirst();
            used -= estimate(dropped.get("content")) + MESSAGE_OVERHEAD;
        }

        List<Map<String, String>> prompt = new ArrayList<>(selected.size() + 1);
        if (system != null) {
            prompt.add(Map.of("role", "system", "content", system));
        }
        prompt.addAll(selected);

        return new Window(List.copyOf(prompt), used, limitTokens, selected.size(), considered,
                selected.size() < considered);
    }

    private static String role(MessageRole role) {
        return role == null ? "user" : role.name().toLowerCase(java.util.Locale.ROOT);
    }
}
