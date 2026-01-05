package com.github.salilvnair.convengine.api.controller;

import com.github.salilvnair.convengine.api.dto.ConversationRequest;
import com.github.salilvnair.convengine.api.dto.ConversationResponse;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.core.ConversationalEngine;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.repo.AuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationalEngine engine;

    private final AuditRepository auditRepository;

    @PostMapping("/message")
    public ConversationResponse message(@RequestBody ConversationRequest request) {

        UUID conversationId = request.getConversationId() != null ? request.getConversationId() : UUID.randomUUID();
        EngineContext engineContext = EngineContext.builder().conversationId(conversationId.toString()).userText(request.getMessage()).build();
        EngineResult result = engine.process(engineContext);

        ConversationResponse res = new ConversationResponse();
        res.setConversationId(conversationId.toString());
        res.setIntent(result.intent());
        res.setState(result.state());
        res.setContext(result.contextJson());

        if (result.payload() instanceof TextPayload(String text)) {
            res.setPayload(
                    new ConversationResponse.ApiPayload("TEXT", text)
            );
        }
        else if (result.payload() instanceof JsonPayload(String json)) {
            res.setPayload(
                    new ConversationResponse.ApiPayload("JSON", json)
            );
        }

        return res;
    }

    @GetMapping("/audit/{conversationId}")
    public List<CeAudit> getAudit(@PathVariable("conversationId") UUID conversationId) {
        return auditRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }
}