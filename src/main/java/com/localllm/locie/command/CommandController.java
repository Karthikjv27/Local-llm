package com.localllm.locie.command;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final CommandService commands;

    public CommandController(CommandService commands) {
        this.commands = commands;
    }

    @GetMapping
    public List<CommandDefinitionView> definitions() {
        return commands.definitions();
    }

    @GetMapping("/history")
    public List<CommandRunView> history() {
        return commands.history();
    }

    @PostMapping("/run")
    public CommandRunView run(@RequestBody RunCommandRequest request) {
        return commands.run(request);
    }
}
