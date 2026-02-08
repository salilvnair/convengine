package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.history.core.ConversationHistoryProvider;
import com.github.salilvnair.convengine.engine.history.model.ConversationTurn;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.response.type.factory.ResponseTypeResolverFactory;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.repo.ResponseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(RulesStep.class)
public class ResponseResolutionStep implements EngineStep {

    private final ResponseRepository responseRepo;
    private final ResponseTypeResolverFactory typeFactory;
    private final AuditService audit;
    private final ConversationHistoryProvider historyProvider;

    @Override
    public StepResult execute(EngineSession session) {

        Optional<CeResponse> responseOptional = resolveResponse(session);

        if(responseOptional.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", session.getIntent());
            payload.put("state", session.getState());
            audit.audit(
                    "RESPONSE_MAPPING_NOT_FOUND",
                    session.getConversationId(),
                    payload
            );
            throw new ConversationEngineException(
                    ConversationEngineErrorCode.RESPONSE_MAPPING_NOT_FOUND,
                    "No response found for intent=" + session.getIntent() + ", state=" + session.getState()
            );
        }
        CeResponse resp = responseOptional.get();
        if (!matches(resp.getStateCode(), session.getState()) && !matches(resp.getStateCode(), "ANY")) {
            session.setState(resp.getStateCode());
            session.getConversation().setStateCode(resp.getStateCode());
        }
        Map<String, Object> responsePayload = new LinkedHashMap<>();
        responsePayload.put("responseId", resp.getResponseId());
        responsePayload.put("intent", session.getIntent());
        responsePayload.put("state", session.getState());
        audit.audit(
                "RESOLVE_RESPONSE",
                session.getConversationId(),
                responsePayload
        );
        List<ConversationTurn> conversationTurns = historyProvider.lastTurns(session.getConversationId(), 10);
        session.setConversationHistory(conversationTurns);
        typeFactory
                .get(resp.getResponseType())
                .resolve(session, resp);

        Object payloadValue = switch (session.getPayload()) {
            case TextPayload(String text) -> text;
            case JsonPayload(String json) -> json;
            case null, default -> null;
        };

        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put("output", payloadValue);
        outputPayload.put("outputFormat", resp.getOutputFormat());
        outputPayload.put("responseType", resp.getResponseType());
        outputPayload.put("responseId", resp.getResponseId());
        outputPayload.put("intent", session.getIntent());
        outputPayload.put("state", session.getState());
        outputPayload.put("context", session.contextDict());
        outputPayload.put("extractedData", session.extractedDataDict());
        audit.audit("ASSISTANT_OUTPUT", session.getConversationId(), outputPayload);

        return new StepResult.Continue();
    }

    private int score(CeResponse response, EngineSession session) {
        int intentScore = matches(response.getIntentCode(), session.getIntent()) ? 2 : 1;
        int stateScore = matches(response.getStateCode(), session.getState())
                ? 2
                : (matches(response.getStateCode(), "ANY") ? 1 : 0);
        int priority = response.getPriority();
        return (intentScore * 10) + (stateScore * 5) - priority;
    }

    private Optional<CeResponse> resolveResponse(EngineSession session) {
        // 1) Strict: exact intent + exact/ANY state
        Optional<CeResponse> strictExactIntent = responseRepo.findAll().stream()
                .filter(CeResponse::isEnabled)
                .filter(r -> matches(r.getIntentCode(), session.getIntent()))
                .filter(r -> matches(r.getStateCode(), session.getState()) || matches(r.getStateCode(), "ANY"))
                .sorted((a, b) -> Integer.compare(score(b, session), score(a, session)))
                .findFirst();
        if (strictExactIntent.isPresent()) {
            return strictExactIntent;
        }

        // 2) Relaxed: exact intent + best state fallback (agnostic; no hardcoded states)
        Optional<CeResponse> relaxedExactIntent = responseRepo.findAll().stream()
                .filter(CeResponse::isEnabled)
                .filter(r -> matches(r.getIntentCode(), session.getIntent()))
                .sorted((a, b) -> Integer.compare(scoreWithStateFallback(b, session), scoreWithStateFallback(a, session)))
                .findFirst();
        if (relaxedExactIntent.isPresent()) {
            return relaxedExactIntent;
        }

        // 3) Generic fallback: null intent + exact/ANY state
        return responseRepo.findAll().stream()
                .filter(CeResponse::isEnabled)
                .filter(r -> r.getIntentCode() == null)
                .filter(r -> matches(r.getStateCode(), session.getState()) || matches(r.getStateCode(), "ANY"))
                .sorted((a, b) -> Integer.compare(score(b, session), score(a, session)))
                .findFirst();
    }

    private int scoreWithStateFallback(CeResponse response, EngineSession session) {
        int stateScore = matches(response.getStateCode(), session.getState())
                ? 2
                : (matches(response.getStateCode(), "ANY") ? 1 : 0);
        int priority = response.getPriority();
        return (stateScore * 5) - priority;
    }

    private boolean matches(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private boolean matchesOrNull(String left, String right) {
        return left == null || matches(left, right);
    }
}
