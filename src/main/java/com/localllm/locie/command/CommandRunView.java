package com.localllm.locie.command;

import java.time.Instant;
import java.util.UUID;

public record CommandRunView(
        UUID id,
        String command,
        String args,
        String status,
        Integer exitCode,
        String stdout,
        String stderr,
        Integer durationMs,
        Instant startedAt,
        Instant finishedAt) {

    static CommandRunView from(CommandRun run) {
        return new CommandRunView(
                run.getId(),
                run.getCommand(),
                run.getArgs(),
                run.getStatus() == null ? null : run.getStatus().name(),
                run.getExitCode(),
                run.getStdout(),
                run.getStderr(),
                run.getDurationMs(),
                run.getStartedAt(),
                run.getFinishedAt());
    }
}
