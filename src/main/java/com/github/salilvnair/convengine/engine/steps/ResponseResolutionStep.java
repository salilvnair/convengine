package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.OutputPayload;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.repo.PromptTemplateRepository;
import com.github.salilvnair.convengine.repo.ResponseRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter({
        AutoAdvanceStep.class,
        ValidationStep.class,
        SchemaExtractionStep.class
})
@MustRunBefore({
        PersistConversationStep.class,
        PipelineEndGuardStep.class
})
public class ResponseResolutionStep implements EngineStep {

    private static final String PURPOSE_TEXT_RESPONSE = "TEXT_RESPONSE";
    private static final String PURPOSE_JSON_RESPONSE = "JSON_RESPONSE";
    private static final String PURPOSE_NEED_MORE_INFO_RESPONSE = "NEED_MORE_INFO_RESPONSE";

    private static final String STATE_NEED_MORE_INFO = "NEED_MORE_INFO";

    private final ResponseRepository responseRepo;
    private final PromptTemplateRepository promptTemplateRepo;
    private final PromptTemplateRenderer renderer;
    private final LlmClient llm;
    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {

        final String resolvedIntent = session.getIntent();
        final String resolvedState = session.getState();

        CeResponse resp =
                responseRepo
                        .findFirstByEnabledTrueAndStateCodeAndIntentCodeOrderByPriorityAsc(resolvedState, resolvedIntent)
                        .or(() -> responseRepo.findFirstByEnabledTrueAndStateCodeAndIntentCodeIsNullOrderByPriorityAsc(resolvedState))
                        .or(() -> responseRepo.findFirstByEnabledTrueAndStateCodeOrderByPriorityAsc("ANY"))
                        .orElseThrow(() -> new IllegalStateException("No fallback response configured in ce_response"));
        audit.audit("RESOLVE_RESPONSE", session.getConversationId(),
                "{\"response_id\":\"" + resp.getResponseId() + "\"}");

        OutputPayload payload;

        if ("TEXT".equalsIgnoreCase(resp.getOutputFormat()) &&
                "EXACT".equalsIgnoreCase(resp.getResponseType())) {

            payload = new TextPayload(resp.getExactText());
            session.getConversation().setLastAssistantJson(jsonText(resp.getExactText()));

            audit.audit("RESPONSE_EXACT", session.getConversationId(),
                    "{\"text\":\"" + JsonUtil.escape(resp.getExactText()) + "\"}");

            session.setPayload(payload);
            return new StepResult.Continue();
        }

        if ("TEXT".equalsIgnoreCase(resp.getOutputFormat())) {

            String purpose = STATE_NEED_MORE_INFO.equalsIgnoreCase(resolvedState)
                    ? PURPOSE_NEED_MORE_INFO_RESPONSE
                    : PURPOSE_TEXT_RESPONSE;

            CePromptTemplate template = resolvePromptTemplate(purpose, resolvedIntent);

            PromptTemplateContext promptTemplateContext = PromptTemplateContext
                                                            .builder()
                                                            .context(session.getContextJson())
                                                            .userInput(session.getUserText())
                                                            .schemaJson(session.getResolvedSchema() != null ? session.getResolvedSchema().getJsonSchema() : null)
                                                            .containerDataJson(session.getContainerDataJson())
                                                            .validationJson(session.getValidationTablesJson())
                                                            .build();
            String userPrompt = renderer.render(template.getUserPrompt(), promptTemplateContext);
            String systemPrompt = renderer.render(template.getSystemPrompt(), promptTemplateContext);

            audit.audit("RESOLVE_RESPONSE_PROMPT_SELECTED", session.getConversationId(),
                    "{\"purpose\":\"" + JsonUtil.escape(purpose) + "\",\"templateId\":" + template.getTemplateId() + "}");

            LlmInvocationContext.set(session.getConversationId(), resolvedIntent, resolvedState);

            audit.audit("RESOLVE_RESPONSE_LLM_INPUT", session.getConversationId(),
                    "{\"system_prompt\":\"" + JsonUtil.escape(systemPrompt) +
                            "\",\"user_prompt\":\"" + JsonUtil.escape(userPrompt) +
                            "\",\"derivation_hint\":\"" + JsonUtil.escape(safe(resp.getDerivationHint())) +
                            "\",\"context\":\"" + JsonUtil.escape(session.getContextJson()) + "\"}");

            String text = llm.generateText(
                    systemPrompt + "\n\n" + userPrompt + "\n\n" + safe(resp.getDerivationHint()),
                    session.getContextJson()
            );

            audit.audit("RESOLVE_RESPONSE_LLM_OUTPUT", session.getConversationId(),
                    "{\"text\":\"" + JsonUtil.escape(text) + "\"}");

            payload = new TextPayload(text);
            session.getConversation().setLastAssistantJson(jsonText(text));

            session.setPayload(payload);
            return new StepResult.Continue();
        }

        // JSON response
        String purpose = PURPOSE_JSON_RESPONSE;
        CePromptTemplate template = resolvePromptTemplate(purpose, resolvedIntent);
        PromptTemplateContext promptTemplateContext = PromptTemplateContext
                                                        .builder()
                                                        .context(session.getContextJson())
                                                        .userInput(session.getUserText())
                                                        .schemaJson(session.getResolvedSchema() != null ? session.getResolvedSchema().getJsonSchema() : null)
                                                        .containerDataJson(session.getContainerDataJson())
                                                        .validationJson(session.getValidationTablesJson())
                                                        .build();
        String systemPrompt = renderer.render(template.getSystemPrompt(), promptTemplateContext);
        String userPrompt = renderer.render(template.getUserPrompt(), promptTemplateContext);

        audit.audit("RESOLVE_RESPONSE_PROMPT_SELECTED", session.getConversationId(),
                "{\"purpose\":\"" + JsonUtil.escape(purpose) + "\",\"templateId\":" + template.getTemplateId() + "}");

        LlmInvocationContext.set(session.getConversationId(), resolvedIntent, resolvedState);

        audit.audit("RESOLVE_RESPONSE_LLM_INPUT", session.getConversationId(),
                "{\"system_prompt\":\"" + JsonUtil.escape(systemPrompt) +
                        "\",\"user_prompt\":\"" + JsonUtil.escape(userPrompt) +
                        "\",\"derivation_hint\":\"" + JsonUtil.escape(safe(resp.getDerivationHint())) +
                        "\",\"context\":\"" + JsonUtil.escape(session.getContextJson()) + "\"}");

        String json = llm.generateJson(
                systemPrompt + "\n\n" + userPrompt + "\n\n" + safe(resp.getDerivationHint()),
                resp.getJsonSchema(),
                session.getContextJson()
        );

        audit.audit("RESOLVE_RESPONSE_LLM_OUTPUT", session.getConversationId(),
                "{\"json\":\"" + JsonUtil.escape(json) + "\"}");

        payload = new JsonPayload(json);
        session.getConversation().setLastAssistantJson(json);

        session.setPayload(payload);
        return new StepResult.Continue();
    }

    private CePromptTemplate resolvePromptTemplate(String purpose, String intentCode) {
        return promptTemplateRepo
                .findFirstByEnabledTrueAndPurposeAndIntentCodeOrderByCreatedAtDesc(purpose, intentCode)
                .orElseGet(() -> promptTemplateRepo
                        .findFirstByEnabledTrueAndPurposeAndIntentCodeIsNullOrderByCreatedAtDesc(purpose)
                        .orElseThrow(() -> new IllegalStateException("No enabled ce_prompt_template found for purpose=" + purpose)));
    }

    private String jsonText(String text) {
        return "{\"type\":\"TEXT\",\"value\":\"" + JsonUtil.escape(text) + "\"}";
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
