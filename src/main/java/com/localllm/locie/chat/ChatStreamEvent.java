package com.localllm.locie.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One server-sent event: a token chunk, the persisted reply, or a failure. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStreamEvent(String type, String text, MessageView message, String error) {

    public static ChatStreamEvent token(String text) {
        return new ChatStreamEvent("token", text, null, null);
    }

    public static ChatStreamEvent done(MessageView message) {
        return new ChatStreamEvent("done", null, message, null);
    }

    public static ChatStreamEvent error(String error) {
        return new ChatStreamEvent("error", null, null, error);
    }
}
