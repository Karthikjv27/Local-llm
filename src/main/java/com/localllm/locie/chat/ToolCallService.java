package com.localllm.locie.chat;

import com.localllm.locie.common.ToolCallNotFoundException;
import com.localllm.locie.model.LlmProvider;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a model's proposal into a recorded row and, only after the user approves it, into a run.
 *
 * <p>Approval re-checks the command through {@link ToolRunner}, which validates it against the
 * allowlist again: the stored proposal is treated as untrusted input, not as permission.
 */
@Slf4j
@Service
public class ToolCallService {

    private static final int MAX_RESULT_CHARS = 8_000;

    private final ToolCallRepository toolCalls;
    private final MessageRepository messages;
    private final ToolRunner runner;

    public ToolCallService(ToolCallRepository toolCalls, MessageRepository messages, ToolRunner runner) {
        this.toolCalls = toolCalls;
        this.messages = messages;
        this.runner = runner;
    }

    @Transactional(readOnly = true)
    public List<ToolCallView> list(UUID chatId) {
        Map<String, ToolRunner.ToolInfo> catalogue = catalogue();
        return toolCalls.findByChatIdOrderByCreatedAtAsc(chatId).stream()
                .map(call -> ToolCallView.from(call, catalogue))
                .toList();
    }

    /**
     * Stores every proposal, including ones that can never run. A proposal outside the allowlist is
     * recorded as {@code FAILED} and never becomes actionable in the UI.
     */
    @Transactional
    public List<ToolCallView> record(Chat chat, UUID messageId, List<LlmProvider.ToolCall> proposals) {
        if (proposals.isEmpty()) {
            return List.of();
        }
        Map<String, ToolRunner.ToolInfo> catalogue = catalogue();
        Message message = messageId == null ? null : messages.getReferenceById(messageId);

        List<ToolCallView> views = new java.util.ArrayList<>(proposals.size());
        for (LlmProvider.ToolCall proposal : proposals) {
            ToolCall call = new ToolCall();
            call.setChat(chat);
            call.setMessage(message);

            String commandId = text(proposal.arguments().get("command_id"));
            String args = text(proposal.arguments().get("args"));
            call.setCommandId(commandId);
            call.setArgs(args == null || args.isBlank() ? null : args.strip());

            if (!ToolRunner.TOOL_NAME.equals(proposal.name())) {
                call.setStatus(ToolCallStatus.FAILED);
                call.setError("Unknown tool: " + proposal.name());
            } else if (commandId == null || !catalogue.containsKey(commandId)) {
                call.setStatus(ToolCallStatus.FAILED);
                call.setError("Command is not in the allowlist: " + commandId);
            } else {
                call.setStatus(ToolCallStatus.PENDING);
            }

            views.add(ToolCallView.from(toolCalls.save(call), catalogue));
        }
        return views;
    }

    @Transactional
    public ToolCallView approve(UUID chatId, UUID callId) {
        ToolCall call = require(chatId, callId);
        if (call.getStatus() != ToolCallStatus.PENDING) {
            throw new IllegalArgumentException("This command has already been decided");
        }
        try {
            ToolRunner.ToolResult result = runner.run(call.getCommandId(), call.getArgs(), chatId);
            call.setCommandRunId(result.runId());
            call.setExitCode(result.exitCode());
            call.setDurationMs(result.durationMs());
            call.setOutput(trim(result.output()));
            call.setError(blankToNull(result.error()));
            call.setStatus(ToolCallStatus.APPROVED);
        } catch (RuntimeException ex) {
            // The allowlist rejected the stored proposal, or the runner failed before starting.
            call.setStatus(ToolCallStatus.FAILED);
            call.setError(trim(messageOf(ex)));
            log.warn("approved tool call {} could not run: {}", callId, ex.toString());
        }
        call.setDecidedAt(Instant.now());
        return ToolCallView.from(toolCalls.save(call), catalogue());
    }

    @Transactional
    public ToolCallView deny(UUID chatId, UUID callId) {
        ToolCall call = require(chatId, callId);
        if (call.getStatus() != ToolCallStatus.PENDING) {
            throw new IllegalArgumentException("This command has already been decided");
        }
        call.setStatus(ToolCallStatus.DENIED);
        call.setDecidedAt(Instant.now());
        return ToolCallView.from(toolCalls.save(call), catalogue());
    }

    @Transactional(readOnly = true)
    public ToolCall require(UUID chatId, UUID callId) {
        return toolCalls.findByIdAndChatId(callId, chatId)
                .orElseThrow(() -> new ToolCallNotFoundException(
                        "Tool call " + callId + " not found in chat " + chatId));
    }

    private Map<String, ToolRunner.ToolInfo> catalogue() {
        return runner.available().stream()
                .collect(Collectors.toMap(ToolRunner.ToolInfo::commandId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_RESULT_CHARS ? value : value.substring(0, MAX_RESULT_CHARS) + "\n…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : trim(value);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
