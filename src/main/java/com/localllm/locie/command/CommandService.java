package com.localllm.locie.command;

import com.localllm.locie.chat.ChatRepository;
import com.localllm.locie.system.CommandRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Validates a request against the allowlist, runs it without a shell, and records the result. */
@Slf4j
@Service
public class CommandService {

    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    private final CommandCatalog catalog;
    private final CommandRunRepository runs;
    private final ChatRepository chats;

    public CommandService(CommandCatalog catalog, CommandRunRepository runs, ChatRepository chats) {
        this.catalog = catalog;
        this.runs = runs;
        this.chats = chats;
    }

    public List<CommandDefinitionView> definitions() {
        return catalog.all().stream().map(CommandDefinitionView::from).toList();
    }

    public List<CommandRunView> history() {
        return runs.findTop50ByOrderByStartedAtDesc().stream().map(CommandRunView::from).toList();
    }

    public CommandRunView run(RunCommandRequest request) {
        CommandCatalog.Definition definition = catalog.require(request.commandId());
        String args = request.args() == null ? "" : request.args().trim();
        validate(definition, args);

        List<String> argv = new ArrayList<>(definition.baseCommand());
        if (!args.isEmpty()) {
            argv.add(args);
        }

        CommandRun run = new CommandRun();
        run.setCommand(String.join(" ", argv));
        run.setArgs(args.isEmpty() ? null : args);
        run.setStatus(CommandStatus.RUNNING);
        if (request.chatId() != null) {
            chats.findById(request.chatId()).ifPresent(run::setChat);
        }
        CommandRun stored = runs.saveAndFlush(run);

        CommandRunner.Result result = CommandRunner.run(argv, definition.timeout(), MAX_OUTPUT_BYTES);

        stored.setExitCode(result.timedOut() ? null : result.exitCode());
        stored.setStdout(result.stdout());
        stored.setStderr(result.stderr().isBlank() ? null : result.stderr());
        stored.setStatus(statusOf(result));
        stored.setFinishedAt(Instant.now());
        if (stored.getStartedAt() != null) {
            stored.setDurationMs((int) Duration.between(stored.getStartedAt(), stored.getFinishedAt()).toMillis());
        }

        log.info("command {} finished with {} in {} ms", stored.getCommand(), stored.getStatus(),
                stored.getDurationMs());
        return CommandRunView.from(runs.save(stored));
    }

    private static void validate(CommandCatalog.Definition definition, String args) {
        if (!definition.requiresArguments()) {
            if (!args.isEmpty()) {
                throw new IllegalArgumentException(definition.label() + " does not take arguments");
            }
            return;
        }
        if (args.isEmpty()) {
            throw new IllegalArgumentException(definition.label() + " needs an argument");
        }
        // a leading dash would be read as a flag by the target program
        if (args.startsWith("-") || !definition.argumentPattern().matcher(args).matches()) {
            throw new IllegalArgumentException("Invalid argument: " + args);
        }
    }

    private static CommandStatus statusOf(CommandRunner.Result result) {
        if (result.timedOut()) {
            return CommandStatus.TIMEOUT;
        }
        return result.exitCode() == 0 ? CommandStatus.OK : CommandStatus.FAILED;
    }
}
