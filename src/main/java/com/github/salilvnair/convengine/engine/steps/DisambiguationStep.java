package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineFlowConfig;
import com.github.salilvnair.convengine.engine.constants.ClarificationConstants;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.dialogue.InteractionPolicyDecision;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePendingAction;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(ActionLifecycleStep.class)
@MustRunBefore(PendingActionStep.class)
public class DisambiguationStep implements EngineStep {

    private final ConvEngineFlowConfig flowConfig;
    private final StaticConfigurationCacheService staticCacheService;
    private final CeConfigResolver configResolver;
    private final PromptTemplateRenderer renderer;
    private final LlmClient llm;
    private final AuditService audit;
    private static final String PROMPT_VAR_CANDIDATE_COUNT = "candidate_count";
    private String questionTemplate;
    private boolean llmEnabled;
    private String llmSystemPrompt;
    private String llmUserPrompt;

    @PostConstruct
    public void init() {
        questionTemplate = configResolver.resolveString(this, "QUESTION_TEMPLATE",
                "I found multiple actions. Which one should I execute: {{options}}?");
        llmEnabled = configResolver.resolveBoolean(this, "ENABLE_LLM", false);
        llmSystemPrompt = configResolver.resolveString(this, "LLM_SYSTEM_PROMPT", """
                You are a workflow assistant.
                Create one concise clarification question for pending-action disambiguation.
                Use only the given options.
                Return plain text only.
                """);
        llmUserPrompt = configResolver.resolveString(this, "LLM_USER_PROMPT", """
                User input:
                {{user_input}}

                Intent:
                {{intent}}

                State:
                {{state}}

                Candidate actions:
                {{options}}

                Candidate count:
                {{candidate_count}}
                """);
    }

    @Override
    public StepResult execute(EngineSession session) {
        if (!flowConfig.getDisambiguation().isEnabled()) {
            return new StepResult.Continue();
        }
        InteractionPolicyDecision decision = parseDecision(
                session.inputParamAsString(ConvEngineInputParamKey.POLICY_DECISION));
        if (decision != InteractionPolicyDecision.EXECUTE_PENDING_ACTION) {
            return new StepResult.Continue();
        }

        String explicitActionKey = session.inputParamAsString(ConvEngineInputParamKey.PENDING_ACTION_KEY);
        if (explicitActionKey != null && !explicitActionKey.isBlank()) {
            return new StepResult.Continue();
        }

        List<CePendingAction> candidates = staticCacheService.findEligiblePendingActionsByIntentAndState(
                session.getIntent(),
                session.getState());
        if (candidates == null || candidates.size() <= 1) {
            return new StepResult.Continue();
        }

        int bestPriority = candidates.getFirst().getPriority() == null ? Integer.MAX_VALUE
                : candidates.getFirst().getPriority();
        List<CePendingAction> top = candidates.stream()
                .filter(c -> (c.getPriority() == null ? Integer.MAX_VALUE : c.getPriority()) == bestPriority)
                .toList();
        if (top.size() <= 1) {
            return new StepResult.Continue();
        }

        Set<String> options = new LinkedHashSet<>();
        for (CePendingAction row : top) {
            if (row.getActionKey() == null || row.getActionKey().isBlank()) {
                continue;
            }
            String option = row.getActionKey().trim();
            if (row.getDescription() != null && !row.getDescription().isBlank()) {
                option = option + " (" + row.getDescription().trim() + ")";
            }
            options.add(option);
            if (options.size() >= Math.max(1, flowConfig.getDisambiguation().getMaxOptions())) {
                break;
            }
        }
        if (options.isEmpty()) {
            return new StepResult.Continue();
        }

        QuestionResult questionResult = buildQuestion(session, top, options);
        String question = questionResult.question();
        session.setPendingClarificationQuestion(question);
        session.setPendingClarificationReason(ClarificationConstants.REASON_PENDING_ACTION_DISAMBIGUATION);
        session.putInputParam(ConvEngineInputParamKey.POLICY_DECISION,
                InteractionPolicyDecision.RECLASSIFY_INTENT.name());
        session.putInputParam(ConvEngineInputParamKey.PENDING_ACTION_DISAMBIGUATION_REQUIRED, true);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.REASON, ClarificationConstants.MULTIPLE_PENDING_ACTIONS);
        payload.put(ConvEnginePayloadKey.QUESTION, question);
        payload.put(ConvEnginePayloadKey.CANDIDATE_COUNT, top.size());
        payload.put(ConvEnginePayloadKey.OPTIONS, new ArrayList<>(options));
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.STATE, session.getState());
        payload.put(ConvEnginePayloadKey.QUESTION_SOURCE, questionResult.source());
        audit.audit(ConvEngineAuditStage.DISAMBIGUATION_REQUIRED, session.getConversationId(), payload);
        return new StepResult.Continue();
    }

    private InteractionPolicyDecision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            return InteractionPolicyDecision.RECLASSIFY_INTENT;
        }
        try {
            return InteractionPolicyDecision.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return InteractionPolicyDecision.RECLASSIFY_INTENT;
        }
    }

    private QuestionResult buildQuestion(EngineSession session, List<CePendingAction> top, Set<String> options) {
        String fallbackQuestion = renderFallbackQuestion(session, top, options);
        if (!llmEnabled) {
            return new QuestionResult(fallbackQuestion, "TEMPLATE");
        }
        try {
            PromptTemplateContext promptContext = PromptTemplateContext.builder()
                    .templateName("DisambiguationStep")
                    .systemPrompt(llmSystemPrompt)
                    .userPrompt(llmUserPrompt)
                    .context(session.getContextJson())
                    .userInput(session.getUserText())
                    .resolvedUserInput(session.getResolvedUserInput())
                    .standaloneQuery(session.getStandaloneQuery())
                    .extra(disambiguationPromptVars(session, top, options))
                    .session(session)
                    .build();
            String systemPrompt = renderer.render(llmSystemPrompt, promptContext);
            String userPrompt = renderer.render(llmUserPrompt, promptContext);
            LlmInvocationContext.set(session.getConversationId(), session.getIntent(), session.getState());
            String llmQuestion = llm.generateText(systemPrompt + "\n\n" + userPrompt, session.getContextJson());
            if (llmQuestion == null || llmQuestion.isBlank()) {
                return new QuestionResult(fallbackQuestion, "TEMPLATE");
            }
            return new QuestionResult(llmQuestion.trim(), "LLM");
        } catch (Exception ignored) {
            return new QuestionResult(fallbackQuestion, "TEMPLATE");
        }
    }

    private String renderFallbackQuestion(EngineSession session, List<CePendingAction> top, Set<String> options) {
        String joinedOptions = String.join(", ", options);
        try {
            PromptTemplateContext promptContext = PromptTemplateContext.builder()
                    .templateName("DisambiguationStep - Fallback")
                    .userPrompt(questionTemplate)
                    .context(session.getContextJson())
                    .userInput(session.getUserText())
                    .resolvedUserInput(session.getResolvedUserInput())
                    .standaloneQuery(session.getStandaloneQuery())
                    .extra(disambiguationPromptVars(session, top, options))
                    .session(session)
                    .build();
            String rendered = renderer.render(questionTemplate, promptContext).trim();
            if (!rendered.isBlank()) {
                return rendered;
            }
        } catch (Exception ignored) {
        }
        return "I found multiple actions. Which one should I execute: " + joinedOptions + "?";
    }

    private Map<String, Object> disambiguationPromptVars(EngineSession session, List<CePendingAction> top,
            Set<String> options) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(ConvEnginePayloadKey.OPTIONS, String.join(", ", options));
        vars.put(PROMPT_VAR_CANDIDATE_COUNT, top.size());
        vars.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        vars.put(ConvEnginePayloadKey.STATE, session.getState());
        vars.put(ConvEnginePayloadKey.ACTION_KEYS, top.stream()
                .map(CePendingAction::getActionKey)
                .filter(k -> k != null && !k.isBlank())
                .map(String::trim)
                .distinct()
                .toList());
        return vars;
    }

    private record QuestionResult(String question, String source) {
    }
}
