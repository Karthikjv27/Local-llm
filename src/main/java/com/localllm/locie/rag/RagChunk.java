package com.localllm.locie.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A passage of a document and the vector that represents it. */
@Entity
@Table(name = "rag_chunks")
@Getter
@Setter
public class RagChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private RagDocument document;

    /** Position inside the document, kept so a result can be cited and ordered. */
    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** Comma-separated floats; see the migration for why this is text and not pgvector. */
    @Column(nullable = false, columnDefinition = "text")
    private String embedding;
}
