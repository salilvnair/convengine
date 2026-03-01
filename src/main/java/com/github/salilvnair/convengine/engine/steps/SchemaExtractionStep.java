package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.constants.ConvEngineValue;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.schema.ConvEngineSchemaComputation;
import com.github.salilvnair.convengine.engine.schema.ConvEngineSchemaResolver;
import com.github.salilvnair.convengine.engine.schema.ConvEngineSchemaResolverFactory;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.OutputType;
import com.github.salilvnair.convengine.engine.type.RulePhase;
import com.github.salilvnair.convengine.entity.CeOutputSchema;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
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
    private final StaticConfigurationCacheService staticCacheService;
    private final PromptTemplateRenderer renderer;
    private final LlmClient llm;
    private final AuditService audit;
    private final ConvEngineSchemaResolverFactory schemaResolverFactory;
    private final RulesStep rulesStep;
    private final VerboseMessagePublisher verbosePublisher;

    @Override
    public StepResult execute(EngineSession session) {

        if (Boolean.TRUE.equals(session.getInputParams().get(ConvEngineInputParamKey.SKIP_SCHEMA_EXTRACTION))) {
            hydrateSchemaWithoutExtraction(session);
            session.syncFromConversation(true);
            return new StepResult.Continue();
        }

        String intent = session.getIntent();
        String state = session.getState();

        CeOutputSchema schema = resolveSchema(session).orElse(null);

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

    private void hydrateSchemaWithoutExtraction(EngineSession session) {
        String intent = session.getIntent();
        CeOutputSchema schema = resolveSchema(session).orElse(null);
        if (schema == null) {
            session.addPromptTemplateVars();
            return;
        }
        ConvEngineSchemaResolver schemaResolver = schemaResolverFactory.get(schema.getJsonSchema());
        Map<String, Object> schemaFieldDetails = schemaResolver.schemaFieldDetails(schema.getJsonSchema());
        ConvEngineSchemaComputation computation = schemaResolver.compute(schema.getJsonSchema(),
                safeJson(session.getContextJson()), schemaFieldDetails);
        session.setResolvedSchema(schema);
        session.setSchemaComplete(computation.schemaComplete());
        session.setSchemaHasAnyValue(computation.hasAnySchemaValue());
        session.setMissingRequiredFields(computation.missingFields());
        session.setMissingFieldOptions(computation.missingFieldOptions());
        session.putInputParam(ConvEngineInputParamKey.MISSING_FIELDS, computation.missingFields());
        session.putInputParam(ConvEngineInputParamKey.MISSING_FIELD_OPTIONS, computation.missingFieldOptions());
        session.putInputParam(ConvEngineInputParamKey.SCHEMA_FIELD_DETAILS, schemaFieldDetails);
        session.putInputParam(ConvEngineInputParamKey.SCHEMA_DESCRIPTION,
                schema.getDescription() == null ? "" : schema.getDescription());
        session.putInputParam(ConvEngineInputParamKey.SCHEMA_ID, schema.getSchemaId());
        session.addPromptTemplateVars();
    }

    private void runExtraction(EngineSession session, CeOutputSchema schema) {

        ConvEngineSchemaResolver schemaResolver = schemaResolverFactory.get(schema.getJsonSchema());

        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put(ConvEnginePayloadKey.SCHEMA_ID, schema.getSchemaId());
        audit.audit(ConvEngineAuditStage.SCHEMA_EXTRACTION_START, session.getConversationId(), startPayload);
        verbosePublisher.publish(session, "SchemaExtractionStep", "SCHEMA_EXTRACTION_START", null, null, false,
                startPayload);

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
                .resolvedUserInput(session.getResolvedUserInput())
                .standaloneQuery(session.getStandaloneQuery())
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
        verbosePublisher.publish(session, "SchemaExtractionStep", "SCHEMA_EXTRACTION_LLM_INPUT", null, null, false,
                llmInputPayload);

        LlmInvocationContext.set(session.getConversationId(), session.getIntent(), session.getState());

        String extractedJson;
        try {
            extractedJson = llm.generateJson(
                    systemPrompt + "\n\n" + userPrompt,
                    schema.getJsonSchema(),
                    safeJson(session.getContextJson()));
        } catch (Exception e) {
            verbosePublisher.publish(session, "SchemaExtractionStep", "SCHEMA_EXTRACTION_LLM_ERROR", null, null, true,
                    Map.of("error", String.valueOf(e.getMessage())));
            throw e;
        }
        session.setLastLlmOutput(extractedJson);
        session.setLastLlmStage("SCHEMA_EXTRACTION");

        Map<String, Object> llmOutputPayload = new LinkedHashMap<>();
        llmOutputPayload.put(ConvEnginePayloadKey.JSON, extractedJson);
        audit.audit(ConvEngineAuditStage.SCHEMA_EXTRACTION_LLM_OUTPUT, session.getConversationId(), llmOutputPayload);
        verbosePublisher.publish(session, "SchemaExtractionStep", "SCHEMA_EXTRACTION_LLM_OUTPUT", null, null, false,
                llmOutputPayload);

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
            session.lockIntent(ConvEngineValue.SCHEMA_INCOMPLETE);
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
        verbosePublisher.publish(session, "SchemaExtractionStep", "SCHEMA_STATUS", null, null, false, statusPayload);
        rulesStep.applyRules(session, "SchemaExtractionStep", RulePhase.POST_SCHEMA_EXTRACTION.name());
        session.syncToConversation();
    }

    private CePromptTemplate resolvePromptTemplate(String responseType, EngineSession session) {
        String intentCode = session.getIntent();
        String stateCode = session.getState();
        return staticCacheService
                .findFirstPromptTemplate(responseType, intentCode, stateCode)
                .or(() -> staticCacheService.findFirstPromptTemplate(responseType, intentCode))
                .orElseThrow(() -> new IllegalStateException("No enabled ce_prompt_template found for responseType="
                        + responseType + " and intent=" + intentCode));
    }

    private String safeJson(String json) {
        return (json == null || json.isBlank()) ? "{}" : json;
    }

    private java.util.Optional<CeOutputSchema> resolveSchema(EngineSession session) {
        if (session.getResolvedSchema() != null) {
            return java.util.Optional.of(session.getResolvedSchema());
        }
        String intent = session.getIntent();
        String state = session.getState();
        if (intent == null || intent.isBlank()) {
            return java.util.Optional.empty();
        }
        return resolveSchemaByPersistedId(session)
                .or(() -> staticCacheService.findFirstOutputSchema(intent, state))
                .or(() -> staticCacheService.findFirstOutputSchema(intent, ConvEngineValue.ANY));
    }

    private java.util.Optional<CeOutputSchema> resolveSchemaByPersistedId(EngineSession session) {
        Object schemaIdValue = session.getInputParams().get(ConvEngineInputParamKey.SCHEMA_ID);
        Long schemaId = toLong(schemaIdValue);
        if (schemaId == null) {
            return java.util.Optional.empty();
        }
        return staticCacheService.findOutputSchemaById(schemaId);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
