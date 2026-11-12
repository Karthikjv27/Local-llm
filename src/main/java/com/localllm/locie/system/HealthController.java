package com.localllm.locie.system;

import com.localllm.locie.model.OllamaAdminClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final JdbcTemplate jdbc;
    private final OllamaAdminClient ollama;

    public HealthController(JdbcTemplate jdbc, OllamaAdminClient ollama) {
        this.jdbc = jdbc;
        this.ollama = ollama;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Component database = probeDatabase();
        Component ollamaStatus = ollama.version()
                .map(version -> new Component("UP", "ollama " + version))
                .orElseGet(() -> new Component("DOWN", "unreachable"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP".equals(database.status()) && "UP".equals(ollamaStatus.status()) ? "UP" : "DEGRADED");
        body.put("db", database);
        body.put("ollama", ollamaStatus);
        return body;
    }

    private Component probeDatabase() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
            return new Component("UP", "postgres reachable");
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new Component("DOWN", message.length() > 200 ? message.substring(0, 200) : message);
        }
    }

    public record Component(String status, String detail) {
    }
}
