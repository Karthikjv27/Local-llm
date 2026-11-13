package com.localllm.locie.command;

public record CommandDefinitionView(
        String id,
        String label,
        String description,
        boolean requiresArguments,
        boolean destructive,
        long timeoutSeconds) {

    static CommandDefinitionView from(CommandCatalog.Definition definition) {
        return new CommandDefinitionView(
                definition.id(),
                definition.label(),
                definition.description(),
                definition.requiresArguments(),
                definition.destructive(),
                definition.timeout().toSeconds());
    }
}
