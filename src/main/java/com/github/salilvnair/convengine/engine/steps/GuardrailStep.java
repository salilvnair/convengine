package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineFlowConfig;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.constants.GuardrailConstants;
import com.github.salilvnair.convengine.engine.dialogue.InteractionPolicyDecision;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(DisambiguationStep.class)
@MustRunBefore({PendingActionStep.class, ToolOrchestrationStep.class, McpToolStep.class})
public class GuardrailStep implements EngineStep {

    private final ConvEngineFlowConfig flowConfig;
    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {
        if (!flowConfig.getGuardrail().isEnabled()) {
            return new StepResult.Continue();
        }

        String originalUserText = session.getUserText() == null ? "" : session.getUserText();
        String sanitizedUserText = sanitize(originalUserText);
        if (flowConfig.getGuardrail().isSanitizeInput()) {
            session.putInputParam(ConvEngineInputParamKey.SANITIZED_USER_TEXT, sanitizedUserText);
        }

        boolean sensitive = matchesSensitivePattern(sanitizedUserText);
        boolean approvalRequired = flowConfig.getGuardrail().isRequireApprovalForSensitiveActions() && sensitive;
        boolean approvalGranted = isApprovalGranted(session);
        boolean failClosed = flowConfig.getGuardrail().isApprovalGateFailClosed();
        boolean denied = approvalRequired && (!approvalGranted || failClosed && !approvalGranted);

        if (denied) {
            session.putInputParam(ConvEngineInputParamKey.GUARDRAIL_BLOCKED, true);
            session.putInputParam(ConvEngineInputParamKey.GUARDRAIL_REASON,
                    GuardrailConstants.REASON_SENSITIVE_ACTION_APPROVAL_REQUIRED);
            session.putInputParam(ConvEngineInputParamKey.POLICY_DECISION, InteractionPolicyDecision.RECLASSIFY_INTENT.name());
            session.putInputParam(ConvEngineInputParamKey.SKIP_TOOL_EXECUTION, true);
            session.putInputParam(ConvEngineInputParamKey.SKIP_PENDING_ACTION_EXECUTION, true);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(ConvEnginePayloadKey.RESULT, GuardrailConstants.RESULT_DENY);
            payload.put(ConvEnginePayloadKey.REASON, GuardrailConstants.REASON_SENSITIVE_ACTION_APPROVAL_REQUIRED);
            payload.put(ConvEnginePayloadKey.SENSITIVE, true);
            payload.put(ConvEnginePayloadKey.APPROVAL_GRANTED, approvalGranted);
            payload.put(ConvEnginePayloadKey.USER_TEXT, sanitizedUserText);
            payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
            payload.put(ConvEnginePayloadKey.STATE, session.getState());
            audit.audit(ConvEngineAuditStage.GUARDRAIL_DENY, session.getConversationId(), payload);
            return new StepResult.Continue();
        }

        session.putInputParam(ConvEngineInputParamKey.GUARDRAIL_BLOCKED, false);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.RESULT, GuardrailConstants.RESULT_ALLOW);
        payload.put(ConvEnginePayloadKey.SENSITIVE, sensitive);
        payload.put(ConvEnginePayloadKey.APPROVAL_REQUIRED, approvalRequired);
        payload.put(ConvEnginePayloadKey.APPROVAL_GRANTED, approvalGranted);
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.STATE, session.getState());
        audit.audit(ConvEngineAuditStage.GUARDRAIL_ALLOW, session.getConversationId(), payload);
        return new StepResult.Continue();
    }

    private boolean isApprovalGranted(EngineSession session) {
        Object fromInput = session.getInputParams().get(GuardrailConstants.INPUT_APPROVAL_GRANTED);
        if (!(fromInput instanceof Boolean)) {
            fromInput = session.getInputParams().get(GuardrailConstants.INPUT_APPROVAL_GRANTED_CAMEL);
        }
        if (fromInput instanceof Boolean b) {
            return b;
        }
        Map<String, Object> context = session.contextDict();
        Object approval = context.get(GuardrailConstants.CONTEXT_APPROVAL);
        if (approval instanceof Map<?, ?> map) {
            Object granted = map.get(GuardrailConstants.CONTEXT_GRANTED);
            if (granted instanceof Boolean b) {
                return b;
            }
            if (granted instanceof String s) {
                return Boolean.parseBoolean(s.trim());
            }
        }
        return false;
    }

    private boolean matchesSensitivePattern(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String rawPattern : flowConfig.getGuardrail().getSensitivePatterns()) {
            if (rawPattern == null || rawPattern.isBlank()) {
                continue;
            }
            try {
                if (Pattern.compile(rawPattern, Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private String sanitize(String userText) {
        if (userText == null) {
            return "";
        }
        String collapsed = userText.replaceAll("[\\r\\n\\t]+", " ");
        return collapsed.replaceAll("\\s{2,}", " ").trim();
    }
}
