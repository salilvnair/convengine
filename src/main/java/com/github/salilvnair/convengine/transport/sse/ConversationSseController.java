package com.github.salilvnair.convengine.transport.sse;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversation/stream")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "convengine.transport.sse", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationSseController {

    private final AuditSseService auditSseService;

    @GetMapping(value = "/{conversationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("conversationId") UUID conversationId) {
        return auditSseService.subscribe(conversationId);
    }
}
