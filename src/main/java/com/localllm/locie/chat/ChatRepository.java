package com.localllm.locie.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChatRepository extends JpaRepository<Chat, UUID> {

    List<Chat> findByArchivedFalseOrderByPinnedDescUpdatedAtDesc();

    List<Chat> findByArchivedFalseAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(String query);

    List<Chat> findByArchivedTrueOrderByUpdatedAtDesc();

    List<Chat> findByArchivedTrueAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(String query);

    /**
     * Title or message-content match. Written as EXISTS rather than a join so one chat cannot appear
     * twice when several of its messages match, and so ORDER BY works with DISTINCT removed.
     */
    @Query("""
            select c from Chat c
            where c.archived = false
              and (lower(c.title) like lower(concat('%', :query, '%'))
                   or exists (select 1 from Message m
                              where m.chat = c
                                and lower(m.content) like lower(concat('%', :query, '%'))))
            order by c.pinned desc, c.updatedAt desc
            """)
    List<Chat> search(@Param("query") String query);

    /** Bumps the chat's last-activity stamp so the sidebar ordering reflects new messages. */
    @Modifying
    @Transactional
    @Query("update Chat c set c.updatedAt = :now where c.id = :id")
    void touch(@Param("id") UUID id, @Param("now") Instant now);
}
