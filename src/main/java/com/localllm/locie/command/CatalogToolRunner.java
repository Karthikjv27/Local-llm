package com.localllm.locie.command;

import com.localllm.locie.chat.ToolRunner;
import com.localllm.locie.model.LlmProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Exposes the command allowlist as one model-facing tool.
 *
 * <p>The model picks a {@code command_id} from an enum built out of the catalog, so "only allowlisted
 * commands" is a property of the schema rather than a check that could be forgotten. Approval runs
 * the request through {@link CommandService}, which validates the identifier and argument pattern
 * again - the model's arguments are never trusted.
 */
@Component
public class CatalogToolRunner implements ToolRunner {

    private final CommandCatalog catalog;
    private final CommandService commands;

    public CatalogToolRunner(CommandCatalog catalog, CommandService commands) {
        this.catalog = catalog;
        this.commands = commands;
    }

    @Override
    public List<ToolInfo> available() {
        return catalog.all().stream()
                .map(definition -> new ToolInfo(
                        definition.id(),
                        definition.label(),
                        definition.description(),
                        definition.requiresArguments(),
                        definition.destructive()))
                .toList();
    }

    @Override
    public List<LlmProvider.Tool> tools() {
        List<ToolInfo> available = available();
        if (available.isEmpty()) {
            return List.of();
        }
        List<String> ids = available.stream().map(ToolInfo::commandId).toList();
        String description = """
                Run one command from the user's allowlist. Only the ids listed here are accepted; \
                there is no shell and no way to pass flags. Use it when the answer depends on the \
                user's machine, then answer from the output.
                Commands: """ + describe(available);

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", Map.of(
                        "command_id", Map.of(
                                "type", "string",
                                "enum", ids,
                                "description", "Identifier of the allowlisted command to run."),
                        "args", Map.of(
                                "type", "string",
                                "description", "The single argument a command needs, for example a model name. "
                                        + "Omit for commands that take none.")),
                "required", List.of("command_id"));

        return List.of(new LlmProvider.Tool(TOOL_NAME, description, parameters));
    }

    @Override
    public ToolResult run(String commandId, String args, UUID chatId) {
        CommandRunView view = commands.run(new RunCommandRequest(commandId, args, chatId));
        return new ToolResult(
                view.id(),
                view.command(),
                view.status(),
                view.exitCode(),
                view.durationMs(),
                output(view),
                view.stderr());
    }

    private static String output(CommandRunView view) {
        StringBuilder text = new StringBuilder();
        if (view.stdout() != null) {
            text.append(view.stdout().strip());
        }
        if (view.stderr() != null && !view.stderr().isBlank()) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append("[stderr]\n").append(view.stderr().strip());
        }
        return text.toString();
    }

    private static String describe(List<ToolInfo> available) {
        StringBuilder text = new StringBuilder();
        for (ToolInfo info : available) {
            text.append("\n- ").append(info.commandId()).append(" (").append(info.label()).append("): ")
                    .append(info.description());
            if (info.requiresArguments()) {
                text.append(" Takes one argument.");
            }
            if (info.destructive()) {
                text.append(" DESTRUCTIVE.");
            }
        }
        return text.toString();
    }
}
