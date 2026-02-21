package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineFlowConfig;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.dialogue.DialogueAct;
import com.github.salilvnair.convengine.engine.dialogue.DialogueActResolveMode;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(AuditUserInputStep.class)
@MustRunBefore(IntentResolutionStep.class)
public class DialogueActStep implements EngineStep {

    private static final Pattern AFFIRM = Pattern.compile(
            "^(\\s)*(yes|yep|yeah|ok|okay|sure|go ahead|do that|please do|confirm|approved?)(\\s)*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATE = Pattern.compile(
            "^(\\s)*(no|nope|nah|cancel|stop|don't|do not)(\\s)*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EDIT = Pattern.compile(
            "^(\\s)*(edit|revise|change|modify|update)(\\s)*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RESET = Pattern.compile(
            "^(\\s)*(reset|restart|start over)(\\s)*$",
            Pattern.CASE_INSENSITIVE);

    private final AuditService audit;
    private final ConvEngineFlowConfig flowConfig;
    private final LlmClient llm;
    private final CeConfigResolver configResolver;

    private final ObjectMapper mapper = new ObjectMapper();

    private String SYSTEM_PROMPT;
    private String USER_PROMPT;
    private String SCHEMA_JSON;

    @PostConstruct
    public void init() {
        SYSTEM_PROMPT = configResolver.resolveString(this, "SYSTEM_PROMPT", """
                You are a dialogue-act classifier.
                Return JSON only with:
                {"dialogueAct":"AFFIRM|NEGATE|EDIT|RESET|QUESTION|NEW_REQUEST","confidence":0.0}
                """);
        USER_PROMPT = configResolver.resolveString(this, "USER_PROMPT", """
                User text:
                %s
                """);
        SCHEMA_JSON = configResolver.resolveString(this, "SCHEMA_PROMPT", """
                {
                  "type":"object",
                  "required":["dialogueAct","confidence"],
                  "properties":{
                    "dialogueAct":{"type":"string","enum":["AFFIRM","NEGATE","EDIT","RESET","QUESTION","NEW_REQUEST"]},
                    "confidence":{"type":"number"}
                  },
                  "additionalProperties":false
                }
                """);
    }

    @Override
    public StepResult execute(EngineSession session) {
        DialogueActResolveMode resolveMode = DialogueActResolveMode.from(
                flowConfig.getDialogueAct().getResolute(),
                DialogueActResolveMode.REGEX_THEN_LLM);
        double regexConfidenceThresholdForLlm = flowConfig.getDialogueAct().getLlmThreshold();
        String userText = session.getUserText() == null ? "" : session.getUserText().trim();

        DialogueActResult regexResult = classifyByRegex(userText);
        DialogueActResult resolved = resolveByMode(session, userText, regexResult, resolveMode,
                regexConfidenceThresholdForLlm);

        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT, resolved.act().name());
        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_CONFIDENCE, resolved.confidence());
        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_SOURCE, resolved.source());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.USER_TEXT, session.getUserText());
        payload.put(ConvEnginePayloadKey.DIALOGUE_ACT, resolved.act().name());
        payload.put(ConvEnginePayloadKey.DIALOGUE_ACT_CONFIDENCE, resolved.confidence());
        payload.put(ConvEnginePayloadKey.DIALOGUE_ACT_SOURCE, resolved.source());
        payload.put("dialogueActResolveMode", resolveMode.name());
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.STATE, session.getState());
        audit.audit(ConvEngineAuditStage.DIALOGUE_ACT_CLASSIFIED, session.getConversationId(), payload);

        return new StepResult.Continue();
    }

    private DialogueActResult resolveByMode(
            EngineSession session,
            String userText,
            DialogueActResult regexResult,
            DialogueActResolveMode resolveMode,
            double regexConfidenceThresholdForLlm) {
        return switch (resolveMode) {
            case REGEX_ONLY -> regexResult.withSource("REGEX");
            case LLM_ONLY -> {
                DialogueActResult llmResult = classifyByLlm(session, userText);
                if (llmResult == null) {
                    yield regexResult.withSource("REGEX_FALLBACK");
                }
                // Prevent false EDIT/RESET when user sends a fresh request sentence.
                if ((llmResult.act() == DialogueAct.EDIT || llmResult.act() == DialogueAct.RESET)
                        && !(EDIT.matcher(userText).matches() || RESET.matcher(userText).matches())) {
                    yield regexResult.withSource("REGEX_GUARD");
                }
                yield llmResult.withSource("LLM");
            }
            case REGEX_THEN_LLM -> {
                if (regexResult.confidence() >= regexConfidenceThresholdForLlm) {
                    yield regexResult.withSource("REGEX");
                }
                DialogueActResult llmResult = classifyByLlm(session, userText);
                if (llmResult == null) {
                    yield regexResult.withSource("REGEX_FALLBACK");
                }
                // Prevent false EDIT/RESET when user sends a fresh request sentence.
                if ((llmResult.act() == DialogueAct.EDIT || llmResult.act() == DialogueAct.RESET)
                        && !(EDIT.matcher(userText).matches() || RESET.matcher(userText).matches())) {
                    yield regexResult.withSource("REGEX_GUARD");
                }
                yield llmResult.withSource("LLM");
            }
        };
    }

    private DialogueActResult classifyByRegex(String userText) {
        if (userText.isEmpty()) {
            return new DialogueActResult(DialogueAct.NEW_REQUEST, 0.40d, "REGEX");
        }
        if (AFFIRM.matcher(userText).matches()) {
            return new DialogueActResult(DialogueAct.AFFIRM, 0.95d, "REGEX");
        }
        if (NEGATE.matcher(userText).matches()) {
            return new DialogueActResult(DialogueAct.NEGATE, 0.95d, "REGEX");
        }
        if (EDIT.matcher(userText).matches()) {
            return new DialogueActResult(DialogueAct.EDIT, 0.95d, "REGEX");
        }
        if (RESET.matcher(userText).matches()) {
            return new DialogueActResult(DialogueAct.RESET, 0.95d, "REGEX");
        }
        if (userText.endsWith("?")) {
            return new DialogueActResult(DialogueAct.QUESTION, 0.70d, "REGEX");
        }
        return new DialogueActResult(DialogueAct.NEW_REQUEST, 0.70d, "REGEX");
    }

    private DialogueActResult classifyByLlm(EngineSession session, String userText) {
        try {
            String systemPrompt = SYSTEM_PROMPT;
            String userPrompt = USER_PROMPT.formatted(userText == null ? "" : userText);
            String schema = SCHEMA_JSON;

            LlmInvocationContext.set(session.getConversationId(), session.getIntent(), session.getState());
            String out = llm.generateJson(systemPrompt + "\n\n" + userPrompt, schema, session.getContextJson());
            JsonNode node = mapper.readTree(out);
            String actRaw = node.path("dialogueAct").asText("").trim();
            DialogueAct act = DialogueAct.valueOf(actRaw.toUpperCase());
            double confidence = clamp(node.path("confidence").asDouble(0.0d));
            return new DialogueActResult(act, confidence, "LLM");
        } catch (Exception ignored) {
            return null;
        }
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private record DialogueActResult(DialogueAct act, double confidence, String source) {
        private DialogueActResult withSource(String source) {
            return new DialogueActResult(act, confidence, source);
        }
    }
}
