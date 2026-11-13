package com.localllm.locie.command;

import com.localllm.locie.chat.ChatRepository;
import com.localllm.locie.system.CommandRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

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

    /** Runs a command to completion and returns the saved run. */
    public CommandRunView run(RunCommandRequest request) {
        Prepared prepared = prepare(request);
        CommandRunner.Result result = CommandRunner.run(prepared.argv(), prepared.definition().timeout(),
                MAX_OUTPUT_BYTES);
        return finish(prepared.run(), result);
    }

    /**
     * Runs a command and streams its output as it arrives, so a slow download shows progress instead
     * of blocking. The saved run is delivered in the final {@code done} event, exactly as the
     * non-streaming endpoint would return it.
     */
    public Flux<ServerSentEvent<CommandStreamEvent>> stream(RunCommandRequest request) {
        Prepared prepared = prepare(request);
        Sinks.Many<ServerSentEvent<CommandStreamEvent>> sink = Sinks.many().unicast().onBackpressureBuffer();

        Thread.ofVirtual().name("command-" + prepared.run().getId()).start(() -> {
            try {
                CommandRunner.Result result = CommandRunner.run(prepared.argv(),
                        prepared.definition().timeout(),
                        MAX_OUTPUT_BYTES,
                        line -> sink.tryEmitNext(sse("line", CommandStreamEvent.line(line))));
                sink.tryEmitNext(sse("done", CommandStreamEvent.done(finish(prepared.run(), result))));
            } catch (RuntimeException ex) {
                log.warn("streamed command {} failed: {}", prepared.run().getCommand(), ex.toString());
                sink.tryEmitNext(sse("error", CommandStreamEvent.error(
                        ex.getMessage() == null ? "command failed" : ex.getMessage())));
            } finally {
                sink.tryEmitComplete();
            }
        });

        return sink.asFlux();
    }

    private record Prepared(CommandCatalog.Definition definition, List<String> argv, CommandRun run) {
    }

    private Prepared prepare(RunCommandRequest request) {
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
        return new Prepared(definition, argv, runs.saveAndFlush(run));
    }

    private CommandRunView finish(CommandRun run, CommandRunner.Result result) {
        run.setExitCode(result.timedOut() ? null : result.exitCode());
        run.setStdout(result.stdout());
        run.setStderr(result.stderr().isBlank() ? null : result.stderr());
        run.setStatus(statusOf(result));
        run.setFinishedAt(Instant.now());
        if (run.getStartedAt() != null) {
            run.setDurationMs((int) Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis());
        }

        log.info("command {} finished with {} in {} ms", run.getCommand(), run.getStatus(), run.getDurationMs());
        return CommandRunView.from(runs.save(run));
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

    private static ServerSentEvent<CommandStreamEvent> sse(String event, CommandStreamEvent data) {
        return ServerSentEvent.<CommandStreamEvent>builder().event(event).data(data).build();
    }
}
