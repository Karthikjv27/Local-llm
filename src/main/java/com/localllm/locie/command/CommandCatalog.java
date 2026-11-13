package com.localllm.locie.command;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The only commands the console may run. Nothing here is user-composed shell: each entry fixes the
 * executable and its subcommand, and argument-taking entries validate the single argument against a
 * pattern, so no option flags or separators can be injected.
 */
@Component
public class CommandCatalog {

    private static final Pattern MODEL_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$");

    public record Definition(
            String id,
            String label,
            String description,
            List<String> baseCommand,
            Pattern argumentPattern,
            boolean destructive,
            Duration timeout) {

        public boolean requiresArguments() {
            return argumentPattern != null;
        }
    }

    private final Map<String, Definition> definitions = new LinkedHashMap<>();

    public CommandCatalog() {
        add(new Definition("ollama-list", "List models", "Installed models and their sizes",
                List.of("ollama", "list"), null, false, Duration.ofSeconds(30)));
        add(new Definition("ollama-ps", "Running models", "Models currently loaded in memory",
                List.of("ollama", "ps"), null, false, Duration.ofSeconds(30)));
        add(new Definition("ollama-show", "Show model", "Full details for one installed model",
                List.of("ollama", "show"), MODEL_NAME, false, Duration.ofSeconds(30)));
        add(new Definition("ollama-pull", "Pull model", "Download a model from the registry",
                List.of("ollama", "pull"), MODEL_NAME, false, Duration.ofMinutes(5)));
        add(new Definition("ollama-stop", "Unload model", "Free the memory held by a loaded model",
                List.of("ollama", "stop"), MODEL_NAME, false, Duration.ofSeconds(60)));
        add(new Definition("ollama-rm", "Delete model", "Remove a model from this machine",
                List.of("ollama", "rm"), MODEL_NAME, true, Duration.ofSeconds(60)));
        add(new Definition("tasklist", "Process list", "Top processes using this machine",
                List.of("tasklist"), null, false, Duration.ofSeconds(30)));
    }

    private void add(Definition definition) {
        definitions.put(definition.id(), definition);
    }

    public List<Definition> all() {
        return List.copyOf(definitions.values());
    }

    public Definition require(String id) {
        Definition definition = id == null ? null : definitions.get(id);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown command: " + id);
        }
        return definition;
    }
}
