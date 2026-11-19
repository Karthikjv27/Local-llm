package com.localllm.locie.rag;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RagChunkRepository extends JpaRepository<RagChunk, UUID> {

    /** Every chunk with its document loaded in one query; ranking happens in memory. */
    @Query("select c from RagChunk c join fetch c.document")
    List<RagChunk> findAllWithDocument();

    void deleteByDocumentId(UUID documentId);
}
