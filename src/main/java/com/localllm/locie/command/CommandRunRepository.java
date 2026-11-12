package com.localllm.locie.command;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommandRunRepository extends JpaRepository<CommandRun, UUID> {

    List<CommandRun> findTop50ByOrderByStartedAtDesc();
}
