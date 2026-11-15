package com.localllm.locie.chat;

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
}
