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

/** Index a folder, inspect what is indexed, preview what a question would retrieve and grade it. */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService rag;
    private final RagEvalService eval;

    public RagController(RagService rag, RagEvalService eval) {
        this.rag = rag;
        this.eval = eval;
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

    /**
     * Grades retrieval against a golden set of {@code question<TAB>expected path} lines and returns
     * recall@k, MRR and each question's rank. See {@code rag-eval.example.txt}.
     */
    @GetMapping("/eval")
    public RagEvalReport evaluate(@RequestParam(name = "path", required = false) String path,
                                  @RequestParam(name = "k", required = false) Integer k) {
        return eval.evaluate(path, k);
    }
}
