package com.localllm.locie.chat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A proposed command, its allowlist label and whatever happened to it. */
public record ToolCallView(
        UUID id,
        String commandId,
        String label,
        String args,
        boolean destructive,
        String status,
        UUID commandRunId,
        Integer exitCode,
        Integer durationMs,
        String output,
        String error,
        Instant createdAt,
        Instant decidedAt) {

    static ToolCallView from(ToolCall call, Map<String, ToolRunner.ToolInfo> catalogue) {
        ToolRunner.ToolInfo info = catalogue.get(call.getCommandId());
        return new ToolCallView(
                call.getId(),
                call.getCommandId(),
                info == null ? call.getCommandId() : info.label(),
                call.getArgs(),
                info != null && info.destructive(),
                call.getStatus() == null ? null : call.getStatus().name(),
                call.getCommandRunId(),
                call.getExitCode(),
                call.getDurationMs(),
                call.getOutput(),
                call.getError(),
                call.getCreatedAt(),
                call.getDecidedAt());
    }
}
