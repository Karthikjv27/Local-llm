package com.localllm.locie.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class OllamaChatClientTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final OllamaChatClient client = new OllamaChatClient(mapper, "http://127.0.0.1:1");

    @Test
    void parsesToolCallsWhoseArgumentsAreAnObject() {
        String frame = """
                {"message":{"role":"assistant","content":"","tool_calls":[{"function":\
                {"name":"run_command","arguments":{"command_id":"ollama-ps","args":""}}}]},"done":true}
                """;

        LlmProvider.Chunk chunk = client.parse(frame);

        assertThat(chunk.done()).isTrue();
        assertThat(chunk.toolCalls()).hasSize(1);
        assertThat(chunk.toolCalls().get(0).name()).isEqualTo("run_command");
        assertThat(chunk.toolCalls().get(0).arguments()).containsEntry("command_id", "ollama-ps");
    }

    @Test
    void parsesToolCallsWhoseArgumentsAreAJsonString() {
        String frame = """
                {"message":{"role":"assistant","content":"","tool_calls":[{"function":\
                {"name":"run_command","arguments":"{\\"command_id\\":\\"ollama-show\\",\\"args\\":\\"llama3:latest\\"}"}}]},"done":true}
                """;

        LlmProvider.Chunk chunk = client.parse(frame);

        assertThat(chunk.toolCalls()).hasSize(1);
        assertThat(chunk.toolCalls().get(0).arguments())
                .containsEntry("command_id", "ollama-show")
                .containsEntry("args", "llama3:latest");
    }

    @Test
    void aPlainTextFrameHasNoToolCalls() {
        LlmProvider.Chunk chunk = client.parse("{\"message\":{\"content\":\"hello\"},\"done\":false}");

        assertThat(chunk.content()).isEqualTo("hello");
        assertThat(chunk.toolCalls()).isEmpty();
    }

    @Test
    void aFrameWithoutToolCallsStillCarriesCounters() {
        LlmProvider.Chunk chunk = client.parse(
                "{\"done\":true,\"eval_count\":12,\"eval_duration\":400000000,\"prompt_eval_count\":7}");

        assertThat(chunk.evalCount()).isEqualTo(12);
        assertThat(chunk.promptEvalCount()).isEqualTo(7);
        assertThat(chunk.evalDuration().toMillis()).isEqualTo(400);
        assertThat(chunk.toolCalls()).isEmpty();
    }
}
