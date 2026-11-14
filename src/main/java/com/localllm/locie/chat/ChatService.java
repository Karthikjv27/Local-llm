package com.localllm.locie.chat;

import com.localllm.locie.common.ChatNotFoundException;
import com.localllm.locie.common.MessageNotFoundException;
import com.localllm.locie.model.LlmProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Conversation storage plus streamed generation. Replies are persisted only after the stream
 * finishes, fails or is stopped, so a message in the database always matches what the user saw.
 */
@Slf4j
@Service
public class ChatService {

    private static final int TITLE_LIMIT = 60;
    private static final String DEFAULT_TITLE = "New chat";

    private final ChatRepository chats;
    private final MessageRepository messages;
    private final LlmProvider llm;
    private final ActiveStreams activeStreams;
    private final int contextTokens;
    private final int historyLimit;

    public ChatService(ChatRepository chats, MessageRepository messages, LlmProvider llm,
                       ActiveStreams activeStreams,
                       @Value("${locie.chat.context-tokens:4096}") int contextTokens,
                       @Value("${locie.chat.history-limit:40}") int historyLimit) {
        this.chats = chats;
        this.messages = messages;
        this.llm = llm;
        this.activeStreams = activeStreams;
        this.contextTokens = contextTokens;
        this.historyLimit = historyLimit;
    }

    @Transactional(readOnly = true)
    public List<ChatSummary> list(String query, boolean archived) {
        boolean blank = query == null || query.isBlank();
        List<Chat> found = archived
                ? (blank
                        ? chats.findByArchivedTrueOrderByUpdatedAtDesc()
                        : chats.findByArchivedTrueAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(query.trim()))
                : (blank
                        ? chats.findByArchivedFalseOrderByPinnedDescUpdatedAtDesc()
                        : chats.search(query.trim()));
        return found.stream().map(ChatSummary::from).toList();
    }

    @Transactional
    public ChatDetail create(CreateChatRequest request) {
        Chat chat = new Chat();
        chat.setTitle(request.title() == null || request.title().isBlank() ? DEFAULT_TITLE : request.title().trim());
        chat.setModel(request.model());
        chat.setSystemPrompt(blankToNull(request.systemPrompt()));
        return detail(chats.saveAndFlush(chat));
    }

    @Transactional(readOnly = true)
    public ChatDetail get(UUID id) {
        return detail(requireChat(id));
    }

    @Transactional
    public ChatDetail update(UUID id, UpdateChatRequest request) {
        Chat chat = requireChat(id);
        if (request.title() != null && !request.title().isBlank()) {
            chat.setTitle(request.title().trim());
        }
        if (request.model() != null && !request.model().isBlank()) {
            chat.setModel(request.model());
        }
        if (request.systemPrompt() != null) {
            chat.setSystemPrompt(blankToNull(request.systemPrompt()));
        }
        if (request.pinned() != null) {
            chat.setPinned(request.pinned());
        }
        if (request.archived() != null) {
            chat.setArchived(request.archived());
        }
        return detail(chats.saveAndFlush(chat));
    }

    @Transactional
    public void delete(UUID id) {
        chats.delete(requireChat(id));
    }

    /**
     * Persists the user's message, then streams the reply as SSE events: {@code token} chunks, a
     * final {@code done} carrying the stored message (with token counts and tok/s), or {@code error}.
     */
    public Flux<ServerSentEvent<ChatStreamEvent>> streamReply(UUID chatId, String content) {
        Chat chat = requireChat(chatId);
        String model = chat.getModel();

        Message userMessage = new Message();
        userMessage.setChat(chat);
        userMessage.setRole(MessageRole.USER);
        userMessage.setContent(content);
        userMessage.setSeq(nextSeq(chatId));
        messages.save(userMessage);
        chats.touch(chatId, Instant.now());

        if (DEFAULT_TITLE.equals(chat.getTitle())) {
            chat.setTitle(titleFrom(content));
            chats.save(chat);
        }

        return stream(chat, model, contextWindow(chat).prompt());
    }

    /**
     * Drops the trailing reply and generates a new one from the same prompt. Refuses when the chat
     * does not currently end with a reply, so a failed regenerate cannot delete a user message.
     */
    public Flux<ServerSentEvent<ChatStreamEvent>> regenerate(UUID chatId) {
        Chat chat = requireChat(chatId);
        List<Message> history = messages.findByChatIdOrderBySeqAsc(chatId);
        if (history.isEmpty() || history.get(history.size() - 1).getRole() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("The last message is not a reply");
        }

        int index = history.size() - 1;
        while (index >= 0 && history.get(index).getRole() == MessageRole.ASSISTANT) {
            messages.delete(history.get(index));
            index--;
        }
        if (index < 0) {
            throw new IllegalArgumentException("Nothing to regenerate");
        }

        chats.touch(chatId, Instant.now());
        return stream(chat, chat.getModel(), contextWindow(chat).prompt());
    }

    /** Signals the reply currently streaming in this chat to stop; false when nothing is running. */
    @Transactional(readOnly = true)
    public boolean stop(UUID id) {
        requireChat(id);
        return activeStreams.stop(id);
    }

    /**
     * Copies the conversation up to {@code fromSeq} (inclusive, default everything) into a new chat,
     * so the user can explore a different continuation without losing the original.
     */
    @Transactional
    public ChatDetail fork(UUID id, Integer fromSeq) {
        Chat source = requireChat(id);
        int limit = fromSeq == null ? Integer.MAX_VALUE : fromSeq;

        Chat copy = new Chat();
        copy.setTitle(source.getTitle() + " (fork)");
        copy.setModel(source.getModel());
        copy.setSystemPrompt(source.getSystemPrompt());
        Chat saved = chats.saveAndFlush(copy);

        for (Message original : messages.findByChatIdOrderBySeqAsc(id)) {
            if (original.getSeq() > limit) {
                break;
            }
            Message clone = new Message();
            clone.setChat(saved);
            clone.setSeq(original.getSeq());
            clone.setRole(original.getRole());
            clone.setContent(original.getContent());
            clone.setModel(original.getModel());
            clone.setPromptTokens(original.getPromptTokens());
            clone.setCompletionTokens(original.getCompletionTokens());
            clone.setLatencyMs(original.getLatencyMs());
            clone.setTokensPerSec(original.getTokensPerSec());
            clone.setError(original.getError());
            clone.setCancelled(original.isCancelled());
            messages.save(clone);
        }
        return detail(saved);
    }

    /** Deletes a message and everything after it, so the conversation can be rewound. */
    @Transactional
    public ChatDetail truncate(UUID chatId, UUID messageId) {
        Chat chat = requireChat(chatId);
        Message target = messages.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException("Message " + messageId + " not found"));
        if (!target.getChat().getId().equals(chatId)) {
            throw new MessageNotFoundException("Message " + messageId + " is not part of this chat");
        }
        int fromSeq = target.getSeq();
        messages.findByChatIdOrderBySeqAsc(chatId).stream()
                .filter(message -> message.getSeq() >= fromSeq)
                .forEach(messages::delete);
        chats.touch(chatId, Instant.now());
        return detail(chat);
    }

    /** Totals and averages over this conversation's stored messages. */
    @Transactional(readOnly = true)
    public ChatStats stats(UUID id) {
        requireChat(id);
        return ChatStats.from(id, messages.findByChatIdOrderBySeqAsc(id));
    }

    /** The whole conversation as a Markdown document, ready to download. */
    @Transactional(readOnly = true)
    public ChatExport export(UUID id) {
        Chat chat = requireChat(id);
        List<Message> history = messages.findByChatIdOrderBySeqAsc(id);

        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(chat.getTitle()).append("\n\n");
        markdown.append("- **Model:** `").append(chat.getModel()).append("`\n");
        markdown.append("- **Created:** ").append(chat.getCreatedAt()).append("\n");
        markdown.append("- **Messages:** ").append(history.size()).append("\n\n---\n\n");

        for (Message message : history) {
            if (message.getRole() == MessageRole.USER) {
                markdown.append("### You\n\n");
            } else {
                markdown.append("### Assistant");
                if (message.getModel() != null && !message.getModel().isBlank()) {
                    markdown.append(" · `").append(message.getModel()).append('`');
                }
                markdown.append("\n\n");
            }

            String text = message.getContent() == null ? "" : message.getContent();
            markdown.append(text.isBlank() ? "_(empty)_" : text.strip()).append("\n\n");

            if (message.getError() != null) {
                markdown.append("> ").append(message.getError()).append("\n\n");
            }
            if (message.isCancelled()) {
                markdown.append("_Stopped by user._\n\n");
            }

            List<String> stats = new ArrayList<>();
            if (message.getCompletionTokens() != null) {
                stats.add(message.getCompletionTokens() + " tokens");
            }
            if (message.getTokensPerSec() != null) {
                stats.add(message.getTokensPerSec() + " tok/s");
            }
            if (message.getLatencyMs() != null) {
                stats.add(message.getLatencyMs() + " ms");
            }
            if (!stats.isEmpty()) {
                markdown.append("<sub>").append(String.join(" · ", stats)).append("</sub>\n\n");
            }
        }

        return new ChatExport(fileName(chat.getTitle()), markdown.toString());
    }

    /** Streams a reply for an already-built prompt and persists the result. */
    private Flux<ServerSentEvent<ChatStreamEvent>> stream(Chat chat, String model,
                                                          List<Map<String, String>> prompt) {
        UUID chatId = chat.getId();
        return Flux.defer(() -> {
            ActiveStreams.Registration registration = activeStreams.register(chatId);
            StringBuilder answer = new StringBuilder();
            AtomicReference<LlmProvider.Chunk> finalChunk = new AtomicReference<>();
            AtomicBoolean saved = new AtomicBoolean(false);
            long startedAt = System.nanoTime();

            return llm.stream(model, prompt)
                    // A stop request flips this flag; the upstream call is then cancelled here, so
                    // Stop works even when the browser never aborts its own request.
                    .takeWhile(chunk -> !registration.stopped())
                    .doOnNext(chunk -> {
                        if (chunk.done()) {
                            finalChunk.set(chunk);
                        }
                    })
                    .mapNotNull(chunk -> {
                        String text = chunk.content();
                        if (text == null || text.isEmpty()) {
                            return null;
                        }
                        answer.append(text);
                        return sse("token", ChatStreamEvent.token(text));
                    })
                    .publishOn(Schedulers.boundedElastic())
                    .concatWith(Flux.defer(() -> {
                        if (!saved.compareAndSet(false, true)) {
                            return Flux.empty();
                        }
                        MessageView stored = registration.stopped()
                                ? persistStopped(chat, model, answer.toString())
                                : persistAnswer(chat, model, answer.toString(), finalChunk.get(), startedAt);
                        return Flux.just(sse("done", ChatStreamEvent.done(stored)));
                    }))
                    .onErrorResume(ex -> {
                        if (saved.compareAndSet(false, true)) {
                            persistFailure(chat, model, answer.toString(), ex);
                        }
                        log.warn("streaming reply failed for chat {}: {}", chatId, ex.toString());
                        return Flux.just(sse("error", ChatStreamEvent.error(messageOf(ex))));
                    })
                    // The browser aborts the fetch when the user presses Stop. Reactor cancels
                    // upstream, so the partial answer is written off the cancelling thread.
                    .doOnCancel(() -> Schedulers.boundedElastic().schedule(() -> {
                        if (saved.compareAndSet(false, true)) {
                            persistStopped(chat, model, answer.toString());
                            log.info("reply for chat {} stopped after {} characters", chatId, answer.length());
                        }
                    }))
                    .doFinally(signal -> registration.close());
        });
    }

    private MessageView persistAnswer(Chat chat, String model, String content,
                                      LlmProvider.Chunk finalChunk, long startedAtNanos) {
        int latencyMs = (int) ((System.nanoTime() - startedAtNanos) / 1_000_000);
        Integer promptTokens = finalChunk == null ? null : finalChunk.promptEvalCount();
        Integer completionTokens = finalChunk == null ? null : finalChunk.evalCount();
        Duration evalDuration = finalChunk == null ? null : finalChunk.evalDuration();
        BigDecimal rate = tokenRate(completionTokens, evalDuration);

        Message message = new Message();
        message.setChat(chat);
        message.setRole(MessageRole.ASSISTANT);
        message.setContent(content);
        message.setModel(model);
        message.setSeq(nextSeq(chat.getId()));
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);
        message.setLatencyMs(latencyMs);
        message.setTokensPerSec(rate);
        Message stored = messages.save(message);

        log.info("assistant reply saved for chat {}: {} tokens at {} tok/s (ollama eval_duration {} ms, "
                        + "load {} ms, wall clock {} ms)",
                chat.getId(), completionTokens, rate,
                evalDuration == null ? null : evalDuration.toMillis(),
                finalChunk == null || finalChunk.loadDuration() == null ? null : finalChunk.loadDuration().toMillis(),
                latencyMs);
        return MessageView.from(stored);
    }

    private void persistFailure(Chat chat, String model, String partial, Throwable error) {
        Message message = new Message();
        message.setChat(chat);
        message.setRole(MessageRole.ASSISTANT);
        message.setContent(partial == null ? "" : partial);
        message.setModel(model);
        message.setSeq(nextSeq(chat.getId()));
        message.setError(messageOf(error));
        messages.save(message);
    }

    /** Keeps what was generated before the user pressed Stop, marked so it is not mistaken for a failure. */
    private MessageView persistStopped(Chat chat, String model, String partial) {
        Message message = new Message();
        message.setChat(chat);
        message.setRole(MessageRole.ASSISTANT);
        message.setContent(partial == null ? "" : partial);
        message.setModel(model);
        message.setSeq(nextSeq(chat.getId()));
        message.setCancelled(true);
        return MessageView.from(messages.save(message));
    }

    /**
     * Exactly Ollama's own counters: {@code eval_count} tokens produced in {@code eval_duration}.
     * No wall-clock estimate and no small-sample guard - the numbers come straight from the daemon.
     */
    private static BigDecimal tokenRate(Integer evalCount, Duration evalDuration) {
        if (evalCount == null || evalCount <= 0 || evalDuration == null
                || evalDuration.isZero() || evalDuration.isNegative()) {
            return null;
        }
        double seconds = evalDuration.toNanos() / 1_000_000_000.0;
        return BigDecimal.valueOf(evalCount / seconds).setScale(2, RoundingMode.HALF_UP);
    }

    private static ServerSentEvent<ChatStreamEvent> sse(String event, ChatStreamEvent data) {
        return ServerSentEvent.<ChatStreamEvent>builder().event(event).data(data).build();
    }

    private ContextBudget.Window contextWindow(Chat chat) {
        return contextWindow(chat, messages.findByChatIdOrderBySeqAsc(chat.getId()));
    }

    /** Applies the message-count cut, then lets the token budget decide what actually fits. */
    private ContextBudget.Window contextWindow(Chat chat, List<Message> history) {
        List<Message> recent = history.size() > historyLimit
                ? history.subList(history.size() - historyLimit, history.size())
                : history;
        return ContextBudget.fit(chat.getSystemPrompt(), recent, contextTokens);
    }

    private ChatDetail detail(Chat chat) {
        List<Message> history = messages.findByChatIdOrderBySeqAsc(chat.getId());
        ContextBudget.Window window = contextWindow(chat, history);
        List<MessageView> messagesView = history.stream().map(MessageView::from).toList();
        return new ChatDetail(
                chat.getId(),
                chat.getTitle(),
                chat.getModel(),
                chat.getSystemPrompt(),
                chat.isPinned(),
                chat.isArchived(),
                chat.getCreatedAt(),
                chat.getUpdatedAt(),
                window.view(),
                messagesView);
    }

    private int nextSeq(UUID chatId) {
        return messages.findTopByChatIdOrderBySeqDesc(chatId).map(message -> message.getSeq() + 1).orElse(1);
    }

    private Chat requireChat(UUID id) {
        return chats.findById(id).orElseThrow(() -> new ChatNotFoundException("Chat " + id + " not found"));
    }

    private static String titleFrom(String content) {
        String compact = content.strip().replaceAll("\\s+", " ");
        return compact.length() <= TITLE_LIMIT ? compact : compact.substring(0, TITLE_LIMIT) + "…";
    }

    private static String fileName(String title) {
        String base = title == null ? "" : title
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "chat";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        return base + ".md";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
