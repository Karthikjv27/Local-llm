package com.localllm.locie.command;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One server-sent event from a streamed command: an output line, the saved run, or a failure. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandStreamEvent(String type, String line, CommandRunView run, String error) {

    public static CommandStreamEvent line(String line) {
        return new CommandStreamEvent("line", line, null, null);
    }

    public static CommandStreamEvent done(CommandRunView run) {
        return new CommandStreamEvent("done", null, run, null);
    }

    public static CommandStreamEvent error(String error) {
        return new CommandStreamEvent("error", null, null, error);
    }
}
