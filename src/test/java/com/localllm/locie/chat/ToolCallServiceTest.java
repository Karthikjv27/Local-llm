package com.localllm.locie.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.localllm.locie.model.LlmProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolCallServiceTest {

    private final ToolCallRepository repository = mock(ToolCallRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final ToolRunner runner = mock(ToolRunner.class);
    private final ToolCallService service = new ToolCallService(repository, messages, runner);

    @BeforeEach
    void setUp() {
        when(runner.available()).thenReturn(List.of(
                new ToolRunner.ToolInfo("ollama-ps", "Running models", "Models loaded now", false, false),
                new ToolRunner.ToolInfo("ollama-rm", "Delete model", "Remove a model", true, true)));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void anAllowlistedProposalIsPendingUntilTheUserDecides() {
        List<ToolCallView> recorded = service.record(new Chat(), null,
                List.of(new LlmProvider.ToolCall("run_command", Map.of("command_id", "ollama-ps"))));

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).status()).isEqualTo("PENDING");
        assertThat(recorded.get(0).label()).isEqualTo("Running models");
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    void aCommandOutsideTheAllowlistIsRecordedButNeverActionable() {
        List<ToolCallView> recorded = service.record(new Chat(), null,
                List.of(new LlmProvider.ToolCall("run_command",
                        Map.of("command_id", "rm", "args", "-rf /"))));

        assertThat(recorded.get(0).status()).isEqualTo("FAILED");
        assertThat(recorded.get(0).error()).contains("not in the allowlist");
    }

    @Test
    void anotherToolNameIsRecordedAsFailed() {
        List<ToolCallView> recorded = service.record(new Chat(), null,
                List.of(new LlmProvider.ToolCall("shell", Map.of("command_id", "ollama-ps"))));

        assertThat(recorded.get(0).status()).isEqualTo("FAILED");
        assertThat(recorded.get(0).error()).contains("Unknown tool");
    }

    @Test
    void approvingRunsTheCommandThroughTheAllowlistAndStoresTheRun() {
        UUID chatId = UUID.randomUUID();
        Chat chat = new Chat();
        chat.setId(chatId);
        ToolCall pending = pending(chatId, "ollama-ps");
        when(repository.findByIdAndChatId(pending.getId(), chatId)).thenReturn(java.util.Optional.of(pending));
        UUID runId = UUID.randomUUID();
        when(runner.run(eq("ollama-ps"), eq(null), eq(chatId))).thenReturn(
                new ToolRunner.ToolResult(runId, "ollama ps", "OK", 0, 120, "NAME ID SIZE", null));

        ToolCallView approved = service.approve(chatId, pending.getId());

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.commandRunId()).isEqualTo(runId);
        assertThat(approved.exitCode()).isZero();
        assertThat(approved.output()).isEqualTo("NAME ID SIZE");
    }

    @Test
    void aDeniedCommandNeverRuns() {
        UUID chatId = UUID.randomUUID();
        ToolCall pending = pending(chatId, "ollama-rm");
        when(repository.findByIdAndChatId(pending.getId(), chatId)).thenReturn(java.util.Optional.of(pending));

        ToolCallView denied = service.deny(chatId, pending.getId());

        assertThat(denied.status()).isEqualTo("DENIED");
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    void aCallCanOnlyBeDecidedOnce() {
        UUID chatId = UUID.randomUUID();
        ToolCall decided = pending(chatId, "ollama-ps");
        decided.setStatus(ToolCallStatus.APPROVED);
        when(repository.findByIdAndChatId(decided.getId(), chatId)).thenReturn(java.util.Optional.of(decided));

        assertThatThrownBy(() -> service.approve(chatId, decided.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been decided");
    }

    private static ToolCall pending(UUID chatId, String commandId) {
        Chat chat = new Chat();
        chat.setId(chatId);
        ToolCall call = new ToolCall();
        call.setId(UUID.randomUUID());
        call.setChat(chat);
        call.setCommandId(commandId);
        call.setStatus(ToolCallStatus.PENDING);
        return call;
    }

    @Test
    void anUndecidedProposalCannotBeContinued() {
        UUID chatId = UUID.randomUUID();
        ToolCall call = pending(chatId, "ollama-ps");
        when(repository.findByIdAndChatId(call.getId(), chatId)).thenReturn(java.util.Optional.of(call));

        assertThatThrownBy(() -> service.claimForContinuation(chatId, call.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Approve or deny");
    }

    @Test
    void aDecidedOutcomeFeedsExactlyOneContinuation() {
        UUID chatId = UUID.randomUUID();
        ToolCall call = pending(chatId, "ollama-ps");
        call.setStatus(ToolCallStatus.APPROVED);
        when(repository.findByIdAndChatId(call.getId(), chatId)).thenReturn(java.util.Optional.of(call));
        when(repository.claimContinuation(call.getId(), chatId)).thenReturn(1, 0);

        service.claimForContinuation(chatId, call.getId());

        assertThatThrownBy(() -> service.claimForContinuation(chatId, call.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been used");
    }
}
