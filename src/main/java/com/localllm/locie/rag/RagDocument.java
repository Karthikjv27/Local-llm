package com.localllm.locie.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** One indexed file: where it came from, and what makes it up to date. */
@Entity
@Table(name = "rag_documents")
@Getter
@Setter
public class RagDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Absolute, normalised path; unique, so re-indexing a folder updates instead of duplicating. */
    @Column(nullable = false, unique = true, columnDefinition = "text")
    private String path;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    /** SHA-256 of the file's text: an unchanged file is skipped without re-embedding it. */
    @Column(name = "content_hash", nullable = false, columnDefinition = "text")
    private String contentHash;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;
}
