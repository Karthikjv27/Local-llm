package com.localllm.locie.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.localllm.locie.command.CatalogToolRunner;
import com.localllm.locie.command.CommandCatalog;
import com.localllm.locie.command.CommandRunRepository;
import com.localllm.locie.command.CommandService;
import com.localllm.locie.model.LlmProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        when(repository.decide(any(), any(), any(), any(), any())).thenReturn(1);
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
        ToolCall pending = pending(chatId, "ollama-ps");
        when(repository.findByIdAndChatId(pending.getId(), chatId)).thenReturn(Optional.of(pending));
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
        when(repository.findByIdAndChatId(pending.getId(), chatId)).thenReturn(Optional.of(pending));

        ToolCallView denied = service.deny(chatId, pending.getId());

        assertThat(denied.status()).isEqualTo("DENIED");
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    void aCallCanOnlyBeDecidedOnce() {
        UUID chatId = UUID.randomUUID();
        ToolCall decided = pending(chatId, "ollama-ps");
        decided.setStatus(ToolCallStatus.APPROVED);
        when(repository.findByIdAndChatId(decided.getId(), chatId)).thenReturn(Optional.of(decided));

        assertThatThrownBy(() -> service.approve(chatId, decided.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been decided");
    }

    /**
     * The prototype of the race: both requests read the row while it was pending, and only the
     * conditional update can tell the loser that the winner already took the decision.
     */
    @Test
    void aSecondApproveOfTheSameProposalCannotRunTheCommandAgain() {
        UUID chatId = UUID.randomUUID();
        ToolCall pending = pending(chatId, "ollama-ps");
        when(repository.findByIdAndChatId(pending.getId(), chatId)).thenReturn(Optional.of(pending));
        when(repository.decide(eq(pending.getId()), eq(chatId), eq(ToolCallStatus.PENDING),
                eq(ToolCallStatus.APPROVED), any())).thenReturn(1, 0);
        when(runner.run(eq("ollama-ps"), eq(null), eq(chatId))).thenReturn(
                new ToolRunner.ToolResult(UUID.randomUUID(), "ollama ps", "OK", 0, 90, "NAME", null));

        service.approve(chatId, pending.getId());

        pending.setStatus(ToolCallStatus.PENDING);
        assertThatThrownBy(() -> service.approve(chatId, pending.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been decided");
        verify(runner, times(1)).run(any(), any(), any());
    }

    @Test
    void aProposalWhoseTurnWasRewoundCanNeverRun() {
        UUID chatId = UUID.randomUUID();
        ToolCall orphaned = pending(chatId, "ollama-ps");
        // What "on delete set null" leaves behind when the proposing turn is rewound.
        orphaned.setMessage(null);
        when(repository.findByIdAndChatId(orphaned.getId(), chatId)).thenReturn(Optional.of(orphaned));

        assertThatThrownBy(() -> service.approve(chatId, orphaned.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("turn is gone");
        verify(repository, never()).decide(any(), any(), any(), any(), any());
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    void invalidArgumentsFailWithoutRunningTheCommand() {
        CommandCatalog catalog = new CommandCatalog();
        ToolRunner validating = new CatalogToolRunner(catalog, new CommandService(
                catalog, mock(CommandRunRepository.class), mock(ChatRepository.class)));
        ToolCallService validatingService = new ToolCallService(repository, messages, validating);

        UUID chatId = UUID.randomUUID();
        ToolCall proposal = proposal(chatId, "ollama-show", "--all");
        when(repository.findByIdAndChatId(proposal.getId(), chatId)).thenReturn(Optional.of(proposal));

        ToolCallView failed = validatingService.approve(chatId, proposal.getId());

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).contains("Invalid argument");
        assertThat(failed.commandRunId()).isNull();
        assertThat(failed.exitCode()).isNull();
    }

    @Test
    void discardingRemovesTheProposalsOfTheDeletedMessagesAndOldOrphans() {
        UUID chatId = UUID.randomUUID();
        List<UUID> messageIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(repository.deleteForMessages(chatId, messageIds)).thenReturn(2);
        when(repository.deleteOrphans(chatId)).thenReturn(1);

        assertThat(service.discard(chatId, messageIds)).isEqualTo(3);

        verify(repository).deleteForMessages(chatId, messageIds);
        verify(repository).deleteOrphans(chatId);
    }

    @Test
    void discardingWithNoMessagesStillSweepsRowsWhoseMessageIsGone() {
        UUID chatId = UUID.randomUUID();
        when(repository.deleteOrphans(chatId)).thenReturn(1);

        assertThat(service.discard(chatId, List.of())).isEqualTo(1);

        verify(repository, never()).deleteForMessages(any(), any());
    }

    @Test
    void anUndecidedProposalCannotBeContinued() {
        UUID chatId = UUID.randomUUID();
        ToolCall call = pending(chatId, "ollama-ps");
        when(repository.findByIdAndChatId(call.getId(), chatId)).thenReturn(Optional.of(call));

        assertThatThrownBy(() -> service.claimForContinuation(chatId, call.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Approve or deny");
    }

    @Test
    void aDecidedOutcomeFeedsExactlyOneContinuation() {
        UUID chatId = UUID.randomUUID();
        ToolCall call = pending(chatId, "ollama-ps");
        call.setStatus(ToolCallStatus.APPROVED);
        when(repository.findByIdAndChatId(call.getId(), chatId)).thenReturn(Optional.of(call));
        when(repository.claimContinuation(call.getId(), chatId)).thenReturn(1, 0);

        service.claimForContinuation(chatId, call.getId());

        assertThatThrownBy(() -> service.claimForContinuation(chatId, call.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been used");
    }

    private static ToolCall pending(UUID chatId, String commandId) {
        return proposal(chatId, commandId, null);
    }

    private static ToolCall proposal(UUID chatId, String commandId, String args) {
        Chat chat = new Chat();
        chat.setId(chatId);
        ToolCall call = new ToolCall();
        call.setId(UUID.randomUUID());
        call.setChat(chat);
        call.setMessage(reply(chat));
        call.setCommandId(commandId);
        call.setArgs(args);
        call.setStatus(ToolCallStatus.PENDING);
        return call;
    }

    private static Message reply(Chat chat) {
        Message message = new Message();
        message.setId(UUID.randomUUID());
        message.setChat(chat);
        message.setRole(MessageRole.ASSISTANT);
        message.setSeq(2);
        return message;
    }
}
