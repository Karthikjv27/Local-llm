package com.localllm.locie.chat;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chats")
public class ChatController {

    private final ChatService chats;

    public ChatController(ChatService chats) {
        this.chats = chats;
    }

    @GetMapping
    public List<ChatSummary> list(@RequestParam(name = "q", required = false) String query) {
        return chats.list(query);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChatDetail create(@Valid @RequestBody CreateChatRequest request) {
        return chats.create(request);
    }

    @GetMapping("/{id}")
    public ChatDetail get(@PathVariable UUID id) {
        return chats.get(id);
    }

    @PatchMapping("/{id}")
    public ChatDetail update(@PathVariable UUID id, @RequestBody UpdateChatRequest request) {
        return chats.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        chats.delete(id);
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> send(@PathVariable UUID id,
                                                       @Valid @RequestBody SendMessageRequest request) {
        return chats.streamReply(id, request.content());
    }

    @PostMapping(value = "/{id}/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> regenerate(@PathVariable UUID id) {
        return chats.regenerate(id);
    }

    @GetMapping(value = "/{id}/export", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> export(@PathVariable UUID id) {
        ChatExport document = chats.export(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(document.filename()).build().toString())
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(document.markdown());
    }
}
