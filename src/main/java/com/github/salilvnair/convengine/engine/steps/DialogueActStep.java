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
import com.github.salilvnair.convengine.engine.type.RulePhase;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import com.github.salilvnair.convengine.util.JsonUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(AuditUserInputStep.class)
@MustRunBefore(IntentResolutionStep.class)
public class DialogueActStep implements EngineStep {

    private Pattern REGEX_AFFIRM;
    private Pattern REGEX_NEGATE;
    private Pattern REGEX_EDIT;
    private Pattern REGEX_RESET;
    private Pattern REGEX_GREETING;

    private final AuditService audit;
    private final ConvEngineFlowConfig flowConfig;
    private final LlmClient llm;
    private final CeConfigResolver configResolver;
    private final PromptTemplateRenderer renderer;
    private final VerboseMessagePublisher verbosePublisher;
    private final RulesStep rulesStep;

    private final ObjectMapper mapper = new ObjectMapper();

    private String SYSTEM_PROMPT;
    private String USER_PROMPT;
    private String SCHEMA_JSON;

    private String QUERY_REWRITE_SYSTEM_PROMPT;
    private String QUERY_REWRITE_USER_PROMPT;
    private String QUERY_REWRITE_SCHEMA_JSON;

    @PostConstruct
    public void init() {
        REGEX_AFFIRM = Pattern.compile(
                configResolver.resolveString(this, "REGEX_AFFIRM",
                        "^(\\s)*(yes|yep|yeah|ok|okay|sure|go ahead|do that|please do|confirm|approved?)(\\s)*$"),
                Pattern.CASE_INSENSITIVE);
        REGEX_NEGATE = Pattern.compile(
                configResolver.resolveString(this, "REGEX_NEGATE",
                        "^(\\s)*(no|nope|nah|cancel|stop|don't|do not)(\\s)*$"),
                Pattern.CASE_INSENSITIVE);
        REGEX_EDIT = Pattern.compile(
                configResolver.resolveString(this, "REGEX_EDIT", "^(\\s)*(edit|revise|change|modify|update)(\\s)*$"),
                Pattern.CASE_INSENSITIVE);
        REGEX_RESET = Pattern.compile(
                configResolver.resolveString(this, "REGEX_RESET", "^(\\s)*(reset|restart|start over)(\\s)*$"),
                Pattern.CASE_INSENSITIVE);
        REGEX_GREETING = Pattern.compile(
                configResolver.resolveString(this, "REGEX_GREETING",
                        "^(\\s)*(hi|hello|hey|greetings|good morning|good afternoon|good evening|howdy)(\\s)*$"),
                Pattern.CASE_INSENSITIVE);
        SYSTEM_PROMPT = configResolver.resolveString(this, "SYSTEM_PROMPT", """
                You are a dialogue-act classifier.
                Return JSON only with:
                {"dialogueAct":"AFFIRM|NEGATE|ANSWER|EDIT|RESET|QUESTION|NEW_REQUEST","confidence":0.0}
                """);
        USER_PROMPT = configResolver.resolveString(this, "USER_PROMPT", """
                User text:
                {{user_input}}
                """);
        SCHEMA_JSON = configResolver.resolveString(this, "SCHEMA_PROMPT",
                """
                        {
                          "type":"object",
                          "required":["dialogueAct","confidence"],
                          "properties":{
                            "dialogueAct":{"type":"string","enum":[%s]},
                            "confidence":{"type":"number"}
                          },
                          "additionalProperties":false
                        }
                        """.formatted(dialogueActEnumJson()));

        QUERY_REWRITE_SYSTEM_PROMPT = configResolver.resolveString(this, "QUERY_REWRITE_SYSTEM_PROMPT",
                """
                        You are a dialogue-act classifier and intelligent query search rewriter.
                        Using the conversation history, rewrite the user's text into an explicit, standalone query that perfectly describes their intent without needing the conversation history context.
                        Also classify their dialogue act.
                        Return JSON only matching the exact schema.
                        """);
        QUERY_REWRITE_USER_PROMPT = configResolver.resolveString(this, "QUERY_REWRITE_USER_PROMPT", """
                Conversation History:
                {{conversation_history}}

                User text:
                {{user_input}}
                """);
        QUERY_REWRITE_SCHEMA_JSON = configResolver.resolveString(this, "QUERY_REWRITE_SCHEMA_PROMPT",
                """
                        {
                          "type":"object",
                          "required":["dialogueAct","confidence","standaloneQuery"],
                          "properties":{
                            "dialogueAct":{"type":"string","enum":[%s]},
                            "confidence":{"type":"number"},
                            "standaloneQuery":{"type":"string"}
                          },
                          "additionalProperties":false
                        }
                        """.formatted(dialogueActEnumJson()));
    }

    @Override
    public StepResult execute(EngineSession session) {
        DialogueActResolveMode resolveMode = DialogueActResolveMode.from(
                flowConfig.getDialogueAct().getResolute(),
                DialogueActResolveMode.REGEX_THEN_LLM);
        double regexConfidenceThresholdForLlm = flowConfig.getDialogueAct().getLlmThreshold();
        String userText = session.getUserText() == null ? "" : session.getUserText().trim();

        DialogueActResult regexResult = classifyByRegex(session, userText);
        DialogueActResolution resolution = resolveByMode(session, userText, regexResult, resolveMode,
                regexConfidenceThresholdForLlm);
        DialogueActResult resolved = resolution.resolved();

        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_REGEX, regexResult.act().name());
        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_REGEX_CONFIDENCE, regexResult.confidence());
        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_LLM_CANDIDATE,
                resolution.llmCandidate() == null ? null : resolution.llmCandidate().act().name());
        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_LLM_CONFIDENCE,
                resolution.llmCandidate() == null ? null : resolution.llmCandidate().confidence());
        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_LLM_STANDALONE_QUERY,
                resolution.llmCandidate() == null ? null : resolution.llmCandidate().standaloneQuery());
        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_GUARD_APPLIED, resolution.guardApplied());
        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_GUARD_REASON, resolution.guardReason());
        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT, resolved.act().name());
        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_CONFIDENCE, resolved.confidence());
        session.putInputParam(ConvEngineInputParamKey.DIALOGUE_ACT_SOURCE, resolved.source());
        boolean hasStandaloneQuery = resolved.standaloneQuery() != null && !resolved.standaloneQuery().isBlank();
        if (hasStandaloneQuery) {
            session.setStandaloneQuery(resolved.standaloneQuery());
        } else {
            session.setStandaloneQuery(null);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.USER_TEXT, session.getUserText());
        payload.put(ConvEnginePayloadKey.DIALOGUE_ACT, resolved.act().name());
        payload.put(ConvEnginePayloadKey.DIALOGUE_ACT_CONFIDENCE, resolved.confidence());
        payload.put(ConvEnginePayloadKey.DIALOGUE_ACT_SOURCE, resolved.source());
        if (hasStandaloneQuery) {
            payload.put(ConvEnginePayloadKey.STANDALONE_QUERY, resolved.standaloneQuery());
        }
        payload.put(ConvEnginePayloadKey.DIALOGUE_ACT_RESOLVE_MODE, resolveMode.name());
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.STATE, session.getState());
        audit.audit(ConvEngineAuditStage.DIALOGUE_ACT_CLASSIFIED, session.getConversationId(), payload);

        rulesStep.applyRules(session, "DialogueActStep", RulePhase.POST_DIALOGUE_ACT.name());

        return new StepResult.Continue();
    }

    private DialogueActResolution resolveByMode(
            EngineSession session,
            String userText,
            DialogueActResult regexResult,
            DialogueActResolveMode resolveMode,
            double regexConfidenceThresholdForLlm) {
        return switch (resolveMode) {
            case REGEX_ONLY -> new DialogueActResolution(regexResult.withSource("REGEX"), null, false, null);
            case LLM_ONLY -> {
                DialogueActResult llmResult = classifyByLlm(session, userText);
                if (llmResult == null) {
                    yield new DialogueActResolution(regexResult.withSource("REGEX_FALLBACK"), null, false, null);
                }
                // Prevent false EDIT/RESET when user sends a fresh request sentence.
                if (shouldGuardToRegex(userText, llmResult)) {
                    yield new DialogueActResolution(regexResult.withSource("REGEX_GUARD"), llmResult, true,
                            guardReason(llmResult));
                }
                yield new DialogueActResolution(llmResult.withSource("LLM"), llmResult, false, null);
            }
            case REGEX_THEN_LLM -> {
                if (regexResult.confidence() >= regexConfidenceThresholdForLlm) {
                    yield new DialogueActResolution(regexResult.withSource("REGEX"), null, false, null);
                }
                DialogueActResult llmResult = classifyByLlm(session, userText);
                if (llmResult == null) {
                    yield new DialogueActResolution(regexResult.withSource("REGEX_FALLBACK"), null, false, null);
                }
                // Prevent false EDIT/RESET when user sends a fresh request sentence.
                if (shouldGuardToRegex(userText, llmResult)) {
                    yield new DialogueActResolution(regexResult.withSource("REGEX_GUARD"), llmResult, true,
                            guardReason(llmResult));
                }
                yield new DialogueActResolution(llmResult.withSource("LLM"), llmResult, false, null);
            }
        };
    }

    private boolean shouldGuardToRegex(String userText, DialogueActResult llmResult) {
        if (llmResult == null) {
            return false;
        }
        if (llmResult.act() == DialogueAct.RESET) {
            return !REGEX_RESET.matcher(userText).matches();
        }
        return false;
    }

    private String guardReason(DialogueActResult llmResult) {
        if (llmResult == null) {
            return null;
        }
        return switch (llmResult.act()) {
            case RESET -> "REGEX_GUARD_RESET";
            case EDIT -> "REGEX_GUARD_EDIT";
            default -> null;
        };
    }

    private DialogueActResult classifyByRegex(EngineSession session, String userText) {
        if (userText.isEmpty()) {
            return new DialogueActResult(DialogueAct.NEW_REQUEST, 0.40d, "REGEX", null);
        }
        if (REGEX_AFFIRM.matcher(userText).matches()) {
            return new DialogueActResult(DialogueAct.AFFIRM, 0.95d, "REGEX", null);
        }
        if (REGEX_NEGATE.matcher(userText).matches()) {
            return new DialogueActResult(DialogueAct.NEGATE, 0.95d, "REGEX", null);
        }
        if (REGEX_EDIT.matcher(userText).matches()) {
            return new DialogueActResult(DialogueAct.EDIT, 0.95d, "REGEX", null);
        }
        if (REGEX_RESET.matcher(userText).matches()) {
            return new DialogueActResult(DialogueAct.RESET, 0.95d, "REGEX", null);
        }
        if (REGEX_GREETING.matcher(userText).matches()) {
            return new DialogueActResult(DialogueAct.GREETING, 0.95d, "REGEX", null);
        }
        if (userText.endsWith("?")) {
            return new DialogueActResult(DialogueAct.QUESTION, 0.70d, "REGEX", null);
        }
        if ((session.hasPendingClarification() || session.isIntentLocked())
                && userText.length() <= 80
                && !userText.contains("?")) {
            return new DialogueActResult(DialogueAct.ANSWER, 0.75d, "REGEX", null);
        }
        return new DialogueActResult(DialogueAct.NEW_REQUEST, 0.70d, "REGEX", null);
    }

    private String dialogueActEnumJson() {
        return Arrays.stream(DialogueAct.values())
                .map(DialogueAct::name)
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(","));
    }

    private DialogueActResult classifyByLlm(EngineSession session, String userText) {
        try {
            boolean useQueryRewrite = flowConfig.getQueryRewrite().isEnabled()
                    && session.conversionHistory() != null
                    && !session.conversionHistory().isEmpty();

            String systemPrompt;
            String userPrompt;
            String schema;

            if (useQueryRewrite) {
                systemPrompt = QUERY_REWRITE_SYSTEM_PROMPT;
                userPrompt = QUERY_REWRITE_USER_PROMPT;
                schema = QUERY_REWRITE_SCHEMA_JSON;
                session.setQueryRewritten(true);
            } else {
                systemPrompt = SYSTEM_PROMPT;
                userPrompt = USER_PROMPT.formatted(userText == null ? "" : userText);
                schema = SCHEMA_JSON;
            }

            PromptTemplateContext promptTemplateContext = PromptTemplateContext
                    .builder()
                    .templateName("DialogueActResult")
                    .systemPrompt(systemPrompt)
                    .userPrompt(userPrompt)
                    .schemaJson(schema)
                    .context(session.getContextJson())
                    .userInput(session.getUserText())
                    .resolvedUserInput(session.getResolvedUserInput())
                    .standaloneQuery(session.getStandaloneQuery())
                    .conversationHistory(JsonUtil.toJson(session.conversionHistory()))
                    .extra(session.promptTemplateVars())
                    .session(session)
                    .build();
            systemPrompt = renderer.render(systemPrompt, promptTemplateContext);
            userPrompt = renderer.render(userPrompt, promptTemplateContext);

            LlmInvocationContext.set(session.getConversationId(), session.getIntent(), session.getState());
            Map<String, Object> inputPayload = new LinkedHashMap<>();
            inputPayload.put(ConvEnginePayloadKey.SYSTEM_PROMPT, systemPrompt);
            inputPayload.put(ConvEnginePayloadKey.USER_PROMPT, userPrompt);
            inputPayload.put(ConvEnginePayloadKey.SCHEMA, schema);
            audit.audit(ConvEngineAuditStage.DIALOGUE_ACT_LLM_INPUT, session.getConversationId(), inputPayload);
            verbosePublisher.publish(session, "DialogueActStep", "DIALOGUE_ACT_LLM_INPUT", null, null, false,
                    inputPayload);
            String out;
            try {
                out = llm.generateJson(systemPrompt + "\n\n" + userPrompt, schema, session.getContextJson());
            } catch (Exception e) {
                Map<String, Object> errorPayload = new LinkedHashMap<>();
                errorPayload.put(ConvEnginePayloadKey.MESSAGE, String.valueOf(e.getMessage()));
                errorPayload.put(ConvEnginePayloadKey.EXCEPTION, e.getClass().getSimpleName());
                audit.audit(ConvEngineAuditStage.DIALOGUE_ACT_LLM_ERROR, session.getConversationId(), errorPayload);
                verbosePublisher.publish(session, "DialogueActStep", "DIALOGUE_ACT_LLM_ERROR", null, null, true,
                        Map.of("error", String.valueOf(e.getMessage())));
                throw e;
            }
            audit.audit(ConvEngineAuditStage.DIALOGUE_ACT_LLM_OUTPUT, session.getConversationId(),
                    Map.of(ConvEnginePayloadKey.JSON, out));
            verbosePublisher.publish(session, "DialogueActStep", "DIALOGUE_ACT_LLM_OUTPUT", null, null, false,
                    Map.of(ConvEnginePayloadKey.JSON, out));
            JsonNode node = mapper.readTree(out);
            String actRaw = node.path("dialogueAct").asText("").trim();
            DialogueAct act = DialogueAct.valueOf(actRaw.toUpperCase());
            double confidence = clamp(node.path("confidence").asDouble(0.0d));
            String standaloneQuery = node.has("standaloneQuery") ? node.path("standaloneQuery").asText(null) : null;
            return new DialogueActResult(act, confidence, "LLM", standaloneQuery);
        } catch (Exception ignored) {
            return null;
        }
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private record DialogueActResult(DialogueAct act, double confidence, String source, String standaloneQuery) {
        private DialogueActResult withSource(String source) {
            return new DialogueActResult(act, confidence, source, standaloneQuery);
        }
    }

    private record DialogueActResolution(
            DialogueActResult resolved,
            DialogueActResult llmCandidate,
            boolean guardApplied,
            String guardReason) {
    }
}
