package com.localllm.locie.model;

import com.localllm.locie.common.OllamaModelNotFoundException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelCatalogService catalog;

    public ModelController(ModelCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<ModelView> list() {
        return catalog.list();
    }

    @GetMapping("/running")
    public List<RunningModelView> running() {
        return catalog.running();
    }

    /**
     * Capture-all so model names containing slashes (e.g. {@code hf.co/user/model}) resolve.
     */
    @GetMapping("/{*name}")
    public ModelDetailView show(@PathVariable("name") String name) {
        String model = name.startsWith("/") ? name.substring(1) : name;
        if (model.isBlank()) {
            throw new OllamaModelNotFoundException("Model name is required");
        }
        return catalog.show(model);
    }
}
