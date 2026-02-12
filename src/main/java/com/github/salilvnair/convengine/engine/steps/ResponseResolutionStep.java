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
import com.github.salilvnair.convengine.engine.response.service.ResponseTransformerService;
import com.github.salilvnair.convengine.engine.response.type.factory.ResponseTypeResolverFactory;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.ResponseType;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.intent.AgentIntentResolver;
import com.github.salilvnair.convengine.intent.AgentIntentCollisionResolver;
import com.github.salilvnair.convengine.model.*;
import com.github.salilvnair.convengine.repo.PromptTemplateRepository;
import com.github.salilvnair.convengine.repo.ResponseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(RulesStep.class)
public class ResponseResolutionStep implements EngineStep {

    private final ResponseRepository responseRepo;
    private final PromptTemplateRepository promptRepo;
    private final ResponseTypeResolverFactory typeFactory;
    private final AuditService audit;
    private final ConversationHistoryProvider historyProvider;
    private final AgentIntentCollisionResolver agentIntentCollisionResolver;
    private final ResponseTransformerService responseTransformerService;

    @Override
    public StepResult execute(EngineSession session) {

        if(AgentIntentResolver.INTENT_COLLISION_STATE.equals(session.getState())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", session.getIntent());
            payload.put("state", session.getState());
            audit.audit(
                    "INTENT_COLLISION_DETECTED",
                    session.getConversationId(),
                    payload
            );
            agentIntentCollisionResolver.resolve(session);
            return new StepResult.Continue();
        }

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



        CePromptTemplate template = null;
        if(ResponseType.DERIVED.name().equalsIgnoreCase(resp.getResponseType())) {
            template = promptRepo.findAll().stream()
                    .filter(t -> Boolean.TRUE.equals(t.getEnabled()))
                    .filter(t -> resp.getOutputFormat().equalsIgnoreCase(t.getResponseType()))
                    .filter(t -> matchesOrNull(t.getIntentCode(), session.getIntent()))
                    .filter(t -> matchesOrNull(t.getStateCode(), session.getState()) || matches(t.getStateCode(), "ANY"))
                    .max(Comparator.comparingInt(t -> score(t, session)))
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "No ce_prompt_template found for response_type=" +
                                            resp.getOutputFormat() + ", intent=" + session.getIntent() + ", state=" + session.getState()
                            )
                    );
        }
        typeFactory
                .get(resp.getResponseType())
                .resolve(session, PromptTemplate.initFrom(template), ResponseTemplate.initFrom(resp));


        OutputPayload transformedOutput = responseTransformerService.transformIfApplicable(session.getPayload(), session, session.getInputParams());
        session.setPayload(transformedOutput);

        Object payloadValue = switch (session.getPayload()) {
            case TextPayload(String text) -> text;
            case JsonPayload(String json) -> json;
            case null -> null;
        };

        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put("output", payloadValue);
        outputPayload.put("outputFormat", resp.getOutputFormat());
        outputPayload.put("responseType", resp.getResponseType());
        outputPayload.put("responseId", resp.getResponseId());
        outputPayload.put("intent", session.getIntent());
        outputPayload.put("state", session.getState());
        outputPayload.put("context", session.contextDict());
        outputPayload.put("extractedData", session.schemaExtractedDataDict());
        audit.audit("ASSISTANT_OUTPUT", session.getConversationId(), outputPayload);

        return new StepResult.Continue();
    }

    private Optional<CeResponse> resolveResponse(EngineSession session) {
        List<CeResponse> candidates = responseRepo.findAll().stream()
                .filter(CeResponse::isEnabled)
                .filter(r -> matches(r.getIntentCode(), session.getIntent()) || r.getIntentCode() == null)
                .filter(r -> matches(r.getStateCode(), session.getState()) || matches(r.getStateCode(), "ANY"))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        candidates.sort((a, b) -> Integer.compare(responseScore(b, session), responseScore(a, session)));
        return Optional.ofNullable(candidates.getFirst());
    }

    private boolean matches(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private int responseScore(CeResponse response, EngineSession session) {
        int exactState = matches(response.getStateCode(), session.getState()) ? 1 : 0;
        int exactIntent = matches(response.getIntentCode(), session.getIntent()) ? 1 : 0;
        int anyState = matches(response.getStateCode(), "ANY") ? 1 : 0;
        int anyIntent = response.getIntentCode() == null ? 1 : 0;
        int priority = response.getPriority() == null ? 999999 : response.getPriority();
        return (exactState * 1000) + (exactIntent * 100) + (anyState * 10) + anyIntent - priority;
    }

    private boolean matchesOrNull(String left, String right) {
        return left == null || matches(left, right);
    }

    private int score(CePromptTemplate template, EngineSession session) {
        int intentScore = matches(template.getIntentCode(), session.getIntent()) ? 2 : 1;
        int stateScore = matches(template.getStateCode(), session.getState())
                ? 2
                : (matches(template.getStateCode(), "ANY") ? 1 : 0);
        return (intentScore * 10) + (stateScore * 5);
    }
}
