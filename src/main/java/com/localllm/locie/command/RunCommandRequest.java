package com.localllm.locie.command;

import java.util.UUID;

public record RunCommandRequest(String commandId, String args, UUID chatId) {
}
