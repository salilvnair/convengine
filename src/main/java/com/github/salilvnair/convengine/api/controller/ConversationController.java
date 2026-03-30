package com.github.salilvnair.convengine.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.api.dto.ConversationRequest;
import com.github.salilvnair.convengine.api.dto.ConversationResponse;
import com.github.salilvnair.convengine.api.dto.ConversationFeedbackRequest;
import com.github.salilvnair.convengine.api.dto.ConversationFeedbackResponse;
import com.github.salilvnair.convengine.api.dto.AuditTraceResponse;
import com.github.salilvnair.convengine.api.service.ConversationFeedbackService;
import com.github.salilvnair.convengine.audit.AuditTraceService;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.core.ConversationalEngine;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.helper.InputParamsHelper;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.engine.response.service.ResponseTransformerService;
import com.github.salilvnair.convengine.engine.response.type.ResponseTransformType;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.OutputPayload;
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
        private final ConversationFeedbackService feedbackService;
        private final ResponseTransformerService responseTransformerService;

        @PostMapping("/message")
        public ConversationResponse message(@RequestBody ConversationRequest request) {

                UUID conversationId = request.getConversationId() != null
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

                EngineContext engineContext = EngineContext.builder()
                                .conversationId(conversationId.toString())
                                .userText(request.getMessage())
                                .inputParams(inputParams)
                                .userInputParams(userInputParams)
                                .build();

                try {
                        EngineResult result = engine.process(engineContext);
                        OutputPayload transformedPayload = transformIfApplicable(engineContext, result);
                        ConversationResponse res = new ConversationResponse();
                        res.setSuccess(true);
                        res.setConversationId(conversationId.toString());
                        res.setIntent(result.intent());
                        res.setState(result.state());
                        res.setContext(result.contextJson());

                        if (transformedPayload instanceof TextPayload textPayload) {
                                res.setPayload(
                                                new ConversationResponse.ApiPayload("TEXT", textPayload.text()));
                        } else if (transformedPayload instanceof JsonPayload jsonPayload) {
                                res.setPayload(
                                                new ConversationResponse.ApiPayload("JSON", jsonPayload.json()));
                        }

                        return res;
                }
                catch (ConversationEngineException ex) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put(ConvEnginePayloadKey.ERROR_CODE, ex.getErrorCode());
                        payload.put(ConvEnginePayloadKey.MESSAGE, ex.getMessage());
                        payload.put(ConvEnginePayloadKey.RECOVERABLE, ex.isRecoverable());
                        if (ex.getMetaData() != null) {
                                payload.put("_meta", ex.getMetaData());
                        }
                        audit.audit(
                                        "ENGINE_KNOWN_FAILURE",
                                        conversationId,
                                        JsonUtil.toJson(payload));
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
                                                                                        ex.isRecoverable()))));
                        return error;
                }
                catch (Exception ex) {
                        if (looksLikeLlmServiceDown(ex)) {
                                Map<String, Object> payload = new LinkedHashMap<>();
                                payload.put(ConvEnginePayloadKey.ERROR_CODE, ConversationEngineErrorCode.LLM_CALL_FAILED.name());
                                payload.put(ConvEnginePayloadKey.MESSAGE, "LLM service is currently unavailable. Please try again.");
                                payload.put(ConvEnginePayloadKey.RECOVERABLE, true);
                                payload.put(ConvEnginePayloadKey.EXCEPTION, String.valueOf(ex));
                                audit.audit(
                                                "ENGINE_KNOWN_FAILURE",
                                                conversationId,
                                                JsonUtil.toJson(payload));
                                ConversationResponse error = new ConversationResponse();
                                error.setConversationId(conversationId.toString());
                                error.setIntent("ERROR");
                                error.setState("ERROR");
                                error.setPayload(
                                                new ConversationResponse.ApiPayload(
                                                                "ERROR",
                                                                JsonUtil.toJson(
                                                                                new ErrorPayload(
                                                                                                ConversationEngineErrorCode.LLM_CALL_FAILED.name(),
                                                                                                "LLM service is currently unavailable. Please try again.",
                                                                                                true))));
                                return error;
                        }
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put(ConvEnginePayloadKey.EXCEPTION, String.valueOf(ex));
                        payload.put(ConvEnginePayloadKey.MESSAGE, ex.getMessage());
                        payload.put(ConvEnginePayloadKey.RECOVERABLE, false);
                        audit.audit(
                                        "ENGINE_UNKNOWN_FAILURE",
                                        conversationId,
                                        JsonUtil.toJson(payload));
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
                                                                                        false))));
                        return error;
                } finally {
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

        @PostMapping("/feedback")
        public ConversationFeedbackResponse saveFeedback(@RequestBody ConversationFeedbackRequest request) {
                return feedbackService.submit(request);
        }

        // ----------------------------------------
        // Internal error payload (UI contract)
        // ----------------------------------------
        record ErrorPayload(
                        String errorCode,
                        String message,
                        boolean recoverable) {
        }

        private OutputPayload transformIfApplicable(EngineContext engineContext, EngineResult result) {
                if (result == null || result.payload() == null) {
                        return null;
                }
                if (result.intent() == null || result.state() == null) {
                        return result.payload();
                }
                EngineSession session = engineContext.getSession();
                return responseTransformerService.transformIfApplicable(
                                result.payload(),
                                session,
                                session.getInputParams(),
                                ResponseTransformType.FINAL);
        }

        private boolean looksLikeLlmServiceDown(Throwable throwable) {
                Throwable cursor = throwable;
                while (cursor != null) {
                        String msg = cursor.getMessage();
                        String normalized = msg == null ? "" : msg.toLowerCase();
                        if (normalized.contains("llm")
                                        || normalized.contains("openai")
                                        || normalized.contains("anthropic")
                                        || normalized.contains("connection refused")
                                        || normalized.contains("timed out")
                                        || normalized.contains("timeout")
                                        || normalized.contains("service unavailable")
                                        || normalized.contains("502")
                                        || normalized.contains("503")
                                        || normalized.contains("504")
                                        || normalized.contains("ce_llm_call_log.prompt_text")
                                        || normalized.contains("prompttext")) {
                                return true;
                        }
                        cursor = cursor.getCause();
                }
                return false;
        }
}
