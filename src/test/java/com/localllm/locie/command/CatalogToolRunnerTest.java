package com.localllm.locie.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.localllm.locie.chat.ChatRepository;
import com.localllm.locie.model.LlmProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogToolRunnerTest {

    private final CommandCatalog catalog = new CommandCatalog();
    private final CatalogToolRunner runner = new CatalogToolRunner(catalog, mock(CommandService.class));

    @Test
    void theSchemaEnumIsExactlyTheAllowlist() {
        List<String> offered = commandIds(runner.tools().get(0));

        assertThat(offered).containsExactlyElementsOf(catalog.all().stream()
                .map(CommandCatalog.Definition::id)
                .toList());
    }

    @Test
    void thereIsOnlyOneTool() {
        assertThat(runner.tools()).hasSize(1);
        assertThat(runner.tools().get(0).name()).isEqualTo("run_command");
    }

    @Test
    void destructiveCommandsAreMarkedInTheDescription() {
        String description = runner.tools().get(0).description();

        assertThat(description).contains("ollama-rm").contains("DESTRUCTIVE");
    }

    @Test
    void commandsThatNeedAnArgumentSaySo() {
        assertThat(runner.tools().get(0).description()).contains("ollama-show (Show model)").contains("Takes one argument");
    }

    @SuppressWarnings("unchecked")
    private static List<String> commandIds(LlmProvider.Tool tool) {
        Map<String, Object> properties = (Map<String, Object>) tool.parameters().get("properties");
        Map<String, Object> commandId = (Map<String, Object>) properties.get("command_id");
        return (List<String>) commandId.get("enum");
    }

    @Test
    void runningRejectsACommandOutsideTheAllowlist() {
        CatalogToolRunner real = new CatalogToolRunner(catalog, new CommandService(
                catalog, mock(CommandRunRepository.class), mock(ChatRepository.class)));

        assertThatThrownBy(() -> real.run("rm", "-rf /", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown command");
    }

    @Test
    void runningRejectsFlagsPassedAsArguments() {
        CatalogToolRunner real = new CatalogToolRunner(catalog, new CommandService(
                catalog, mock(CommandRunRepository.class), mock(ChatRepository.class)));

        assertThatThrownBy(() -> real.run("ollama-show", "--all", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid argument");
    }
}
