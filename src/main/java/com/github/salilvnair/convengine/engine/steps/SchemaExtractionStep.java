package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.schema.ConvEngineSchemaComputation;
import com.github.salilvnair.convengine.engine.schema.ConvEngineSchemaResolver;
import com.github.salilvnair.convengine.engine.schema.ConvEngineSchemaResolverFactory;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.OutputType;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class SchemaExtractionStep implements EngineStep {
    private final OutputSchemaRepository outputSchemaRepo;
    private final PromptTemplateRepository promptTemplateRepo;
    private final PromptTemplateRenderer renderer;
    private final LlmClient llm;
    private final AuditService audit;
    private final ConvEngineSchemaResolverFactory schemaResolverFactory;

    @Override
    public StepResult execute(EngineSession session) {

        String intent = session.getIntent();
        String state = session.getState();

        CeOutputSchema schema = outputSchemaRepo.findAll().stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .filter(s -> equalsIgnoreCase(s.getIntentCode(), intent))
                .filter(s -> equalsIgnoreCase(s.getStateCode(), state) || equalsIgnoreCase(s.getStateCode(), "ANY"))
                .min((a, b) -> Integer.compare(priorityOf(a), priorityOf(b)))
                .orElse(null);

        if (schema != null) {
            runExtraction(session, schema);
        } else {
            session.unlockIntent();
            session.setResolvedSchema(null);
            session.setSchemaComplete(false);
            session.setSchemaHasAnyValue(false);
            session.setMissingRequiredFields(new ArrayList<>());
            session.setMissingFieldOptions(new LinkedHashMap<>());
            session.addPromptTemplateVars();
        }

        session.syncFromConversation(true);
        return new StepResult.Continue();
    }

    private void runExtraction(EngineSession session, CeOutputSchema schema) {

        ConvEngineSchemaResolver schemaResolver = schemaResolverFactory.get(schema.getJsonSchema());

        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put(ConvEnginePayloadKey.SCHEMA_ID, schema.getSchemaId());
        audit.audit(ConvEngineAuditStage.SCHEMA_EXTRACTION_START, session.getConversationId(), startPayload);

        CePromptTemplate template = resolvePromptTemplate(OutputType.SCHEMA_JSON.name(), session);

        Map<String, Object> schemaFieldDetails = schemaResolver.schemaFieldDetails(schema.getJsonSchema());
        session.putInputParam(ConvEngineInputParamKey.SCHEMA_FIELD_DETAILS, schemaFieldDetails);
        session.putInputParam(ConvEngineInputParamKey.SCHEMA_DESCRIPTION,
                schema.getDescription() == null ? "" : schema.getDescription());
        session.putInputParam(ConvEngineInputParamKey.SCHEMA_ID, schema.getSchemaId());
        session.setContextJson(schemaResolver.seedContextFromInputParams(
                session.getContextJson(),
                session.getInputParams(),
                schema.getJsonSchema()));
        session.getConversation().setContextJson(session.getContextJson());
        session.addPromptTemplateVars();

        PromptTemplateContext promptTemplateContext = PromptTemplateContext
                .builder()
                .templateName("SchemaExtraction")
                .systemPrompt(template.getSystemPrompt())
                .userPrompt(template.getUserPrompt())
                .context(safeJson(session.getContextJson()))
                .userInput(session.getUserText())
                .schemaJson(schema.getJsonSchema())
                .conversationHistory(JsonUtil.toJson(session.conversionHistory()))
                .extra(session.promptTemplateVars())
                .session(session)
                .build();
        String systemPrompt = renderer.render(template.getSystemPrompt(), promptTemplateContext);
        String userPrompt = renderer.render(template.getUserPrompt(), promptTemplateContext);

        Map<String, Object> llmInputPayload = new LinkedHashMap<>();
        llmInputPayload.put(ConvEnginePayloadKey.SYSTEM_PROMPT, systemPrompt);
        llmInputPayload.put(ConvEnginePayloadKey.USER_PROMPT, userPrompt);
        llmInputPayload.put(ConvEnginePayloadKey.SCHEMA, schema.getJsonSchema());
        llmInputPayload.put(ConvEnginePayloadKey.USER_INPUT, session.getUserText());
        audit.audit(ConvEngineAuditStage.SCHEMA_EXTRACTION_LLM_INPUT, session.getConversationId(), llmInputPayload);

        LlmInvocationContext.set(session.getConversationId(), session.getIntent(), session.getState());

        String extractedJson = llm.generateJson(
                systemPrompt + "\n\n" + userPrompt,
                schema.getJsonSchema(),
                safeJson(session.getContextJson()));
        session.setLastLlmOutput(extractedJson);
        session.setLastLlmStage("SCHEMA_EXTRACTION");

        Map<String, Object> llmOutputPayload = new LinkedHashMap<>();
        llmOutputPayload.put(ConvEnginePayloadKey.JSON, extractedJson);
        audit.audit(ConvEngineAuditStage.SCHEMA_EXTRACTION_LLM_OUTPUT, session.getConversationId(), llmOutputPayload);

        String merged = schemaResolver.mergeContextJson(session.getContextJson(), extractedJson);
        session.setContextJson(merged);
        session.getConversation().setContextJson(merged);

        ConvEngineSchemaComputation computation = schemaResolver.compute(schema.getJsonSchema(), merged,
                schemaFieldDetails);
        session.setSchemaComplete(computation.schemaComplete());
        session.setSchemaHasAnyValue(computation.hasAnySchemaValue());
        session.setMissingRequiredFields(computation.missingFields());
        session.setMissingFieldOptions(computation.missingFieldOptions());
        session.setResolvedSchema(schema);
        if (computation.schemaComplete()) {
            session.unlockIntent();
        } else {
            session.lockIntent("SCHEMA_INCOMPLETE");
        }
        session.putInputParam(ConvEngineInputParamKey.MISSING_FIELDS, computation.missingFields());
        session.putInputParam(ConvEngineInputParamKey.SCHEMA_FIELD_DETAILS, schemaFieldDetails);
        session.putInputParam(ConvEngineInputParamKey.MISSING_FIELD_OPTIONS, computation.missingFieldOptions());
        session.putInputParam(ConvEngineInputParamKey.SCHEMA_DESCRIPTION,
                schema.getDescription() == null ? "" : schema.getDescription());
        session.putInputParam(ConvEngineInputParamKey.SCHEMA_ID, schema.getSchemaId());
        session.addPromptTemplateVars();

        Map<String, Object> statusPayload = new LinkedHashMap<>();
        statusPayload.put(ConvEnginePayloadKey.SCHEMA_COMPLETE, computation.schemaComplete());
        statusPayload.put(ConvEnginePayloadKey.HAS_ANY_SCHEMA_VALUE, computation.hasAnySchemaValue());
        statusPayload.put(ConvEnginePayloadKey.MISSING_REQUIRED_FIELDS, computation.missingFields());
        statusPayload.put(ConvEnginePayloadKey.MISSING_FIELD_OPTIONS, computation.missingFieldOptions());
        statusPayload.put(ConvEnginePayloadKey.SCHEMA_ID, schema.getSchemaId());
        statusPayload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        statusPayload.put(ConvEnginePayloadKey.STATE, session.getState());
        statusPayload.put(ConvEnginePayloadKey.INTENT_LOCKED, session.isIntentLocked());
        statusPayload.put(ConvEnginePayloadKey.INTENT_LOCK_REASON, session.getIntentLockReason());
        statusPayload.put(ConvEnginePayloadKey.CONTEXT, session.contextDict());
        statusPayload.put(ConvEnginePayloadKey.SCHEMA_JSON, session.schemaJson());
        audit.audit(ConvEngineAuditStage.SCHEMA_STATUS, session.getConversationId(), statusPayload);
    }

    private CePromptTemplate resolvePromptTemplate(String responseType, EngineSession session) {
        String intentCode = session.getIntent();
        String stateCode = session.getState();
        return promptTemplateRepo
                .findFirstByEnabledTrueAndResponseTypeAndIntentCodeAndStateCodeOrderByCreatedAtDesc(responseType,
                        intentCode, stateCode)
                .or(() -> promptTemplateRepo.findFirstByEnabledTrueAndResponseTypeAndIntentCodeOrderByCreatedAtDesc(
                        responseType, intentCode))
                .orElseThrow(() -> new IllegalStateException("No enabled ce_prompt_template found for responseType="
                        + responseType + " and intent=" + intentCode));
    }

    private String safeJson(String json) {
        return (json == null || json.isBlank()) ? "{}" : json;
    }

    private boolean equalsIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private int priorityOf(CeOutputSchema schema) {
        return schema.getPriority() == null ? Integer.MAX_VALUE : schema.getPriority();
    }
}
