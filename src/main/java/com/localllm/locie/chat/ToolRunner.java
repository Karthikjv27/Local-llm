package com.localllm.locie.chat;

import com.localllm.locie.model.LlmProvider;
import java.util.List;
import java.util.UUID;

/**
 * The only commands the model may ask for. Implemented by the command module, which owns the
 * allowlist, so this package never depends on it and the model can never reach a shell.
 */
public interface ToolRunner {

    /** The single tool offered to the model; argument validation happens on approval, not here. */
    String TOOL_NAME = "run_command";

    List<ToolInfo> available();

    List<LlmProvider.Tool> tools();

    /** Runs one allowlisted command, recording it like any other run. */
    ToolResult run(String commandId, String args, UUID chatId);

    record ToolInfo(
            String commandId,
            String label,
            String description,
            boolean requiresArguments,
            boolean destructive) {
    }

    record ToolResult(
            UUID runId,
            String command,
            String status,
            Integer exitCode,
            Integer durationMs,
            String output,
            String error) {
    }
}
