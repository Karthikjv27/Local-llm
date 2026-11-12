package com.localllm.locie.chat;

import com.localllm.locie.model.OllamaChatClient;
import com.localllm.locie.model.OllamaStreamChunk;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Conversation storage plus streamed generation. Replies are persisted only after the stream
 * finishes (or fails), so a message in the database always matches what the user saw.
 */
@Slf4j
@Service
public class ChatService {

    private static final int HISTORY_LIMIT = 40;
    private static final int TITLE_LIMIT = 60;
    private static final String DEFAULT_TITLE = "New chat";

    private final ChatRepository chats;
    private final MessageRepository messages;
    private final OllamaChatClient chatStream;

    public ChatService(ChatRepository chats, MessageRepository messages, OllamaChatClient chatStream) {
        this.chats = chats;
        this.messages = messages;
        this.chatStream = chatStream;
    }

    @Transactional(readOnly = true)
    public List<ChatSummary> list(String query) {
        List<Chat> found = query == null || query.isBlank()
                ? chats.findByArchivedFalseOrderByPinnedDescUpdatedAtDesc()
                : chats.findByArchivedFalseAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(query.trim());
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

        List<Map<String, String>> prompt = promptMessages(chatId, chat.getSystemPrompt());

        return Flux.defer(() -> {
            StringBuilder answer = new StringBuilder();
            AtomicReference<OllamaStreamChunk> finalChunk = new AtomicReference<>();
            AtomicBoolean saved = new AtomicBoolean(false);
            long startedAt = System.nanoTime();

            return chatStream.stream(model, prompt)
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
                        MessageView stored = persistAnswer(chat, model, answer.toString(), finalChunk.get(), startedAt);
                        return Flux.just(sse("done", ChatStreamEvent.done(stored)));
                    }))
                    .onErrorResume(ex -> {
                        if (saved.compareAndSet(false, true)) {
                            persistFailure(chat, model, answer.toString(), ex);
                        }
                        log.warn("streaming reply failed for chat {}: {}", chatId, ex.toString());
                        return Flux.just(sse("error", ChatStreamEvent.error(messageOf(ex))));
                    });
        });
    }

    private MessageView persistAnswer(Chat chat, String model, String content,
                                      OllamaStreamChunk finalChunk, long startedAtNanos) {
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

    /** Chat history as Ollama's {@code messages} array, system prompt first. */
    private List<Map<String, String>> promptMessages(UUID chatId, String systemPrompt) {
        List<Map<String, String>> prompt = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.add(roleMessage("system", systemPrompt));
        }

        List<Message> stored = messages.findByChatIdOrderBySeqAsc(chatId);
        List<Message> window = stored.size() > HISTORY_LIMIT
                ? stored.subList(stored.size() - HISTORY_LIMIT, stored.size())
                : stored;

        for (Message message : window) {
            String text = message.getContent() == null ? "" : message.getContent();
            if (message.getRole() == MessageRole.USER) {
                prompt.add(roleMessage("user", text));
            } else if (message.getRole() == MessageRole.ASSISTANT && !text.isBlank()) {
                prompt.add(roleMessage("assistant", text));
            }
        }
        return prompt;
    }

    private static Map<String, String> roleMessage(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private int nextSeq(UUID chatId) {
        return messages.findTopByChatIdOrderBySeqDesc(chatId).map(message -> message.getSeq() + 1).orElse(1);
    }

    private ChatDetail detail(Chat chat) {
        List<MessageView> history = messages.findByChatIdOrderBySeqAsc(chat.getId()).stream()
                .map(MessageView::from)
                .toList();
        return new ChatDetail(
                chat.getId(),
                chat.getTitle(),
                chat.getModel(),
                chat.getSystemPrompt(),
                chat.isPinned(),
                chat.getCreatedAt(),
                chat.getUpdatedAt(),
                history);
    }

    private Chat requireChat(UUID id) {
        return chats.findById(id).orElseThrow(() -> new ChatNotFoundException("Chat " + id + " not found"));
    }

    private static String titleFrom(String content) {
        String compact = content.strip().replaceAll("\\s+", " ");
        return compact.length() <= TITLE_LIMIT ? compact : compact.substring(0, TITLE_LIMIT) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
