package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineFlowConfig;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(RulesStep.class)
@MustRunBefore(ResponseResolutionStep.class)
public class StateGraphStep implements EngineStep {

    private final ConvEngineFlowConfig flowConfig;
    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {
        if (!flowConfig.getStateGraph().isEnabled()) {
            return new StepResult.Continue();
        }
        String fromState = session.getConversation() == null ? null : session.getConversation().getStateCode();
        String toState = session.getState();

        if (fromState == null || fromState.isBlank() || toState == null || toState.isBlank()
                || fromState.equalsIgnoreCase(toState)) {
            return new StepResult.Continue();
        }

        boolean allowed = isAllowedTransition(fromState, toState);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromState", fromState);
        payload.put("toState", toState);
        payload.put("intent", session.getIntent());
        payload.put("validateOnly", true);

        if (allowed) {
            session.putInputParam(ConvEngineInputParamKey.STATE_GRAPH_VALID, true);
            payload.put("allowed", true);
            audit.audit(ConvEngineAuditStage.STATE_GRAPH_VALID, session.getConversationId(), payload);
            return new StepResult.Continue();
        }

        payload.put("allowed", false);
        payload.put("softBlock", flowConfig.getStateGraph().isSoftBlockOnViolation());
        audit.audit(ConvEngineAuditStage.STATE_GRAPH_VIOLATION, session.getConversationId(), payload);
        session.putInputParam(ConvEngineInputParamKey.STATE_GRAPH_VALID, false);
        if (flowConfig.getStateGraph().isSoftBlockOnViolation()) {
            session.putInputParam(ConvEngineInputParamKey.STATE_GRAPH_SOFT_BLOCK, true);
        }
        return new StepResult.Continue();
    }

    private boolean isAllowedTransition(String fromState, String toState) {
        Map<String, List<String>> configured = flowConfig.getStateGraph().getAllowedTransitions();
        if (configured == null || configured.isEmpty()) {
            return true;
        }
        String from = fromState.trim().toUpperCase(Locale.ROOT);
        String to = toState.trim().toUpperCase(Locale.ROOT);
        List<String> allowed = configured.get(from);
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        return allowed.stream().anyMatch(candidate -> to.equalsIgnoreCase(candidate));
    }
}
