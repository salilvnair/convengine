package com.github.salilvnair.convengine.api.controller;

import com.github.salilvnair.convengine.api.dto.ConversationRequest;
import com.github.salilvnair.convengine.api.dto.ConversationResponse;
import com.github.salilvnair.convengine.api.dto.AuditTraceResponse;
import com.github.salilvnair.convengine.audit.AuditTraceService;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.core.ConversationalEngine;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.helper.InputParamsHelper;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.repo.AuditRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationalEngine engine;
    private final AuditRepository auditRepository;
    private final AuditService audit;
    private final AuditTraceService auditTraceService;

    @PostMapping("/message")
    public ConversationResponse message(@RequestBody ConversationRequest request) {

        UUID conversationId =
                request.getConversationId() != null
                        ? request.getConversationId()
                        : UUID.randomUUID();

        Map<String, Object> inputParams = new LinkedHashMap<>();
        Map<String, Object> userInputParams = Collections.emptyMap();
        if (request.getInputParams() != null) {
            inputParams.putAll(request.getInputParams());
            userInputParams = InputParamsHelper.deepCopy(request.getInputParams());
        }
        if (Boolean.TRUE.equals(request.getReset())) {
            inputParams.put("reset", true);
        }

        EngineContext engineContext =
                EngineContext.builder()
                        .conversationId(conversationId.toString())
                        .userText(request.getMessage())
                        .inputParams(inputParams)
                        .userInputParams(userInputParams)
                        .build();

        try {
            EngineResult result = engine.process(engineContext);

            ConversationResponse res = new ConversationResponse();
            res.setSuccess(true);
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
        catch (ConversationEngineException ex) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(ConvEnginePayloadKey.ERROR_CODE, ex.getErrorCode());
            payload.put(ConvEnginePayloadKey.MESSAGE, ex.getMessage());
            payload.put(ConvEnginePayloadKey.RECOVERABLE, ex.isRecoverable());
            audit.audit(
                    "ENGINE_KNOWN_FAILURE",
                    conversationId,
                    JsonUtil.toJson(payload)
            );
            ConversationResponse error = new ConversationResponse();
            error.setConversationId(conversationId.toString());
            error.setIntent("ERROR");
            error.setState("ERROR");

            error.setPayload(
                    new ConversationResponse.ApiPayload(
                            "ERROR",
                            JsonUtil.toJson(
                                    new ErrorPayload(
                                            ex.getErrorCode(),
                                            ex.getMessage(),
                                            ex.isRecoverable()
                                    )
                            )
                    )
            );
            return error;
        }
        catch (Exception ex) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(ConvEnginePayloadKey.EXCEPTION, String.valueOf(ex));
            payload.put(ConvEnginePayloadKey.MESSAGE, ex.getMessage());
            payload.put(ConvEnginePayloadKey.RECOVERABLE, false);
            audit.audit(
                    "ENGINE_UNKNOWN_FAILURE",
                    conversationId,
                    JsonUtil.toJson(payload)
            );
            ConversationResponse error = new ConversationResponse();
            error.setConversationId(conversationId.toString());
            error.setIntent("ERROR");
            error.setState("ERROR");

            error.setPayload(
                    new ConversationResponse.ApiPayload(
                            "ERROR",
                            JsonUtil.toJson(
                                    new ErrorPayload(
                                            null,
                                            ex.getMessage(),
                                            false
                                    )
                            )
                    )
            );
            return error;
        }
        finally {
            // Safety flush for deferred audit mode. In immediate mode this is a no-op.
            audit.flushPending(conversationId);
        }
    }

    @GetMapping("/audit/{conversationId}")
    public List<CeAudit> getAudit(@PathVariable("conversationId") UUID conversationId) {
        return auditRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    @GetMapping("/audit/{conversationId}/trace")
    public AuditTraceResponse getAuditTrace(@PathVariable("conversationId") UUID conversationId) {
        return auditTraceService.trace(conversationId);
    }

    // ----------------------------------------
    // Internal error payload (UI contract)
    // ----------------------------------------
    record ErrorPayload(
            String errorCode,
            String message,
            boolean recoverable
    ) {}
}
