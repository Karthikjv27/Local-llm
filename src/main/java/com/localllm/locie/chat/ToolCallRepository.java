package com.localllm.locie.chat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ToolCallRepository extends JpaRepository<ToolCall, UUID> {

    List<ToolCall> findByChatIdOrderByCreatedAtAsc(UUID chatId);

    Optional<ToolCall> findByIdAndChatId(UUID id, UUID chatId);

    /** Returns 1 only for the first claim, so two requests cannot both generate a continuation. */
    @Modifying
    @Query("update ToolCall t set t.continued = true "
            + "where t.id = :id and t.chat.id = :chatId and t.continued = false")
    int claimContinuation(@Param("id") UUID id, @Param("chatId") UUID chatId);

    /**
     * Returns 1 only for the request that actually moves a proposal out of {@code expected}, so two
     * approve calls (or an approve racing a deny) cannot both act on one proposal.
     */
    @Modifying
    @Query("update ToolCall t set t.status = :next, t.decidedAt = :decidedAt "
            + "where t.id = :id and t.chat.id = :chatId and t.status = :expected")
    int decide(@Param("id") UUID id, @Param("chatId") UUID chatId,
               @Param("expected") ToolCallStatus expected, @Param("next") ToolCallStatus next,
               @Param("decidedAt") Instant decidedAt);

    /** Proposals made by turns that are being deleted along with their messages. */
    @Modifying
    @Query("delete from ToolCall t where t.chat.id = :chatId and t.message.id in :messageIds")
    int deleteForMessages(@Param("chatId") UUID chatId, @Param("messageIds") Collection<UUID> messageIds);

    /**
     * Proposals whose message is already gone. The foreign key nulls {@code message_id} when a
     * message is deleted, so rows from before this cleanup existed are found here.
     */
    @Modifying
    @Query("delete from ToolCall t where t.chat.id = :chatId and t.message is null")
    int deleteOrphans(@Param("chatId") UUID chatId);
}
