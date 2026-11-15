package com.localllm.locie.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCallRepository extends JpaRepository<ToolCall, UUID> {

    List<ToolCall> findByChatIdOrderByCreatedAtAsc(UUID chatId);

    Optional<ToolCall> findByIdAndChatId(UUID id, UUID chatId);
}
