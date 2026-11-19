package com.localllm.locie.rag;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Index a folder, inspect what is indexed, and preview what a question would retrieve. */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService rag;

    public RagController(RagService rag) {
        this.rag = rag;
    }

    @GetMapping("/documents")
    public List<RagDocumentView> documents() {
        return rag.list();
    }

    /** Indexes a folder. Unchanged files are skipped, so this is safe to run again. */
    @PostMapping("/index")
    public RagIndexResult index(@Valid @RequestBody RagIndexRequest request) {
        return rag.index(request.path());
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        rag.delete(id);
    }

    /** The passages a question would put in the prompt - the debugging view of retrieval. */
    @GetMapping("/search")
    public List<RagSnippet> search(@RequestParam("q") String question,
                                   @RequestParam(name = "limit", defaultValue = "5") int limit) {
        return rag.search(question, limit);
    }
}
