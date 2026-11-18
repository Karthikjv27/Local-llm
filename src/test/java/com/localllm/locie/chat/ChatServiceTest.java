package com.localllm.locie.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.localllm.locie.model.LlmProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;

/** Rewind and regenerate must take the proposals of the discarded turns with them. */
class ChatServiceTest {

    private final ChatRepository chats = mock(ChatRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final LlmProvider llm = mock(LlmProvider.class);
    private final ActiveStreams activeStreams = mock(ActiveStreams.class);
    private final ToolRunner toolRunner = mock(ToolRunner.class);
    private final ToolCallService toolCalls = mock(ToolCallService.class);
    private final ChatService service = new ChatService(
            chats, messages, llm, activeStreams, toolRunner, toolCalls, 4096, 40);

    private final Chat chat = new Chat();

    @BeforeEach
    void setUp() {
        chat.setId(UUID.randomUUID());
        chat.setTitle("tool check");
        chat.setModel("qwen2.5-coder:3b");
        when(chats.findById(chat.getId())).thenReturn(Optional.of(chat));
        when(toolCalls.list(chat.getId())).thenReturn(List.of());
        when(llm.stream(any(), any(), any())).thenReturn(Flux.empty());
    }

    @Test
    void rewindingDropsTheProposalsMadeByTheRemovedTurns() {
        Message question = message(1, MessageRole.USER);
        Message proposalTurn = message(2, MessageRole.ASSISTANT);
        Message followUp = message(3, MessageRole.USER);
        when(messages.findByChatIdOrderBySeqAsc(chat.getId()))
                .thenReturn(List.of(question, proposalTurn, followUp));
        when(messages.findById(proposalTurn.getId())).thenReturn(Optional.of(proposalTurn));

        service.truncate(chat.getId(), proposalTurn.getId());

        // Proposals first: their message_id is nulled by the foreign key once the message is gone.
        InOrder order = inOrder(toolCalls, messages);
        order.verify(toolCalls).discard(chat.getId(), List.of(proposalTurn.getId(), followUp.getId()));
        order.verify(messages).delete(proposalTurn);
        order.verify(messages).delete(followUp);
        verify(messages, never()).delete(question);
    }

    @Test
    void regeneratingDropsTheProposalsMadeByTheReplacedReply() {
        Message question = message(1, MessageRole.USER);
        Message reply = message(2, MessageRole.ASSISTANT);
        when(messages.findByChatIdOrderBySeqAsc(chat.getId())).thenReturn(List.of(question, reply));

        service.regenerate(chat.getId());

        InOrder order = inOrder(toolCalls, messages);
        order.verify(toolCalls).discard(chat.getId(), List.of(reply.getId()));
        order.verify(messages).delete(reply);
        verify(messages, never()).delete(question);
    }

    @Test
    void regeneratingWithNothingToReplaceChangesNothing() {
        Message reply = message(1, MessageRole.ASSISTANT);
        when(messages.findByChatIdOrderBySeqAsc(chat.getId())).thenReturn(List.of(reply));

        assertThatThrownBy(() -> service.regenerate(chat.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nothing to regenerate");
        verify(toolCalls, never()).discard(any(), any());
        verify(messages, never()).delete(any());
    }

    private Message message(int seq, MessageRole role) {
        Message message = new Message();
        message.setId(UUID.randomUUID());
        message.setChat(chat);
        message.setSeq(seq);
        message.setRole(role);
        message.setContent(role == MessageRole.USER ? "are any models loaded?" : "");
        return message;
    }
}
