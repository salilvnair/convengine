package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeOutputSchema;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.repo.OutputSchemaRepository;
import com.github.salilvnair.convengine.repo.PromptTemplateRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class SchemaExtractionStep implements EngineStep {

    private static final String PURPOSE_SCHEMA_EXTRACTION = "SCHEMA_EXTRACTION";

    private final OutputSchemaRepository outputSchemaRepo;
    private final PromptTemplateRepository promptTemplateRepo;
    private final PromptTemplateRenderer renderer;
    private final LlmClient llm;
    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {

        String intent = session.getIntent();
        String state = session.getState();

        outputSchemaRepo
                .findFirstByEnabledTrueAndIntentCodeAndStateCodeOrderByPriorityAsc(intent, state)
                .ifPresent(schema -> runExtraction(session, schema));

        session.syncFromConversation();
        return new StepResult.Continue();
    }

    private void runExtraction(EngineSession session, CeOutputSchema schema) {

        audit.audit("SCHEMA_EXTRACTION_START", session.getConversationId(),
                "{\"schemaId\":" + schema.getSchemaId() + "}");

        CePromptTemplate template = resolvePromptTemplate(PURPOSE_SCHEMA_EXTRACTION, session.getIntent());

        PromptTemplateContext promptTemplateContext = PromptTemplateContext
                                                        .builder()
                                                        .context(session.getContextJson())
                                                        .userInput(session.getUserText())
                                                        .schemaJson(session.getResolvedSchema() != null ? session.getResolvedSchema().getJsonSchema() : null)
                                                        .build();
        String systemPrompt = renderer.render(template.getSystemPrompt(), promptTemplateContext);
        String userPrompt = renderer.render(template.getUserPrompt(), promptTemplateContext);

        audit.audit("SCHEMA_EXTRACTION_LLM_INPUT", session.getConversationId(),
                "{\"system_prompt\":\"" + JsonUtil.escape(systemPrompt) +
                        "\",\"user_prompt\":\"" + JsonUtil.escape(userPrompt) +
                        "\",\"schema\":\"" + JsonUtil.escape(schema.getJsonSchema()) +
                        "\",\"userInput\":\"" + JsonUtil.escape(session.getUserText()) + "\"}");

        LlmInvocationContext.set(session.getConversationId(), session.getIntent(), session.getState());

        String extractedJson = llm.generateJson(
                systemPrompt + "\n\n" + userPrompt,
                schema.getJsonSchema(),
                session.getContextJson() + "\nUser input: " + session.getUserText()
        );

        audit.audit("SCHEMA_EXTRACTION_LLM_OUTPUT", session.getConversationId(),
                "{\"json\":\"" + JsonUtil.escape(extractedJson) + "\"}");

        String merged = JsonUtil.merge(session.getContextJson(), extractedJson);
        session.setContextJson(merged);
        session.getConversation().setContextJson(merged);

        boolean complete = JsonUtil.isSchemaComplete(schema.getJsonSchema(), merged);
        session.setSchemaComplete(complete);
        session.setResolvedSchema(schema);

        audit.audit("SCHEMA_STATUS", session.getConversationId(),
                "{\"schemaComplete\":" + complete +
                        ",\"schema\":\"" + JsonUtil.escape(schema.getJsonSchema()) +
                        "\",\"payload\":" + merged + "}");
    }

    private CePromptTemplate resolvePromptTemplate(String purpose, String intentCode) {
        return promptTemplateRepo.findFirstByEnabledTrueAndPurposeAndIntentCodeOrderByCreatedAtDesc(purpose, intentCode)
                .orElseGet(() -> promptTemplateRepo
                        .findFirstByEnabledTrueAndPurposeAndIntentCodeIsNullOrderByCreatedAtDesc(purpose)
                        .orElseThrow(() -> new IllegalStateException("No enabled ce_prompt_template found for purpose=" + purpose)));
    }
}
