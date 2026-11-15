package com.localllm.locie.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One server-sent event: a token chunk, a proposed command, the persisted reply, or a failure. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStreamEvent(String type, String text, MessageView message, ToolCallView tool, String error) {

    public static ChatStreamEvent token(String text) {
        return new ChatStreamEvent("token", text, null, null, null);
    }

    public static ChatStreamEvent tool(ToolCallView tool) {
        return new ChatStreamEvent("tool", null, null, tool, null);
    }

    public static ChatStreamEvent done(MessageView message) {
        return new ChatStreamEvent("done", null, message, null, null);
    }

    public static ChatStreamEvent error(String error) {
        return new ChatStreamEvent("error", null, null, null, error);
    }
}
