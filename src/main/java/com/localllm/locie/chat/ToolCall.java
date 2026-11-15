package com.localllm.locie.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** One command the model asked to run, and what the user decided about it. */
@Entity
@Table(name = "tool_calls")
@Getter
@Setter
public class ToolCall {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_id", nullable = false)
    private Chat chat;

    /** The reply whose stream proposed this call; null when the proposal arrived without text. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private Message message;

    @Column(name = "command_id", nullable = false, columnDefinition = "text")
    private String commandId;

    @Column(columnDefinition = "text")
    private String args;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private ToolCallStatus status = ToolCallStatus.PENDING;

    @Column(name = "command_run_id")
    private UUID commandRunId;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(columnDefinition = "text")
    private String output;

    @Column(columnDefinition = "text")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** True once a continuation has been generated from this outcome; it is single-use. */
    @Column(nullable = false)
    private boolean continued = false;
}
