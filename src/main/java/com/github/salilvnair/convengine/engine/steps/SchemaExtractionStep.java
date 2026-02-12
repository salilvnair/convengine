package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
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
import java.util.List;
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
            session.setResolvedSchema(null);
            session.setSchemaComplete(false);
            session.setSchemaHasAnyValue(false);
            session.setMissingRequiredFields(new ArrayList<>());
            session.setMissingFieldOptions(new LinkedHashMap<>());
            ensureSchemaPromptVars(session);
            session.putInputParam("context", session.contextDict());
            session.putInputParam("schema_extracted_data", session.schemaExtractedDataDict());
            session.putInputParam("session", session.sessionDict());
        }

        session.syncFromConversation();
        return new StepResult.Continue();
    }

    private void runExtraction(EngineSession session, CeOutputSchema schema) {

        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put("schemaId", schema.getSchemaId());
        audit.audit("SCHEMA_EXTRACTION_START", session.getConversationId(), startPayload);

        CePromptTemplate template = resolvePromptTemplate(OutputType.SCHEMA_EXTRACTED_DATA.name(), session.getIntent());

        Map<String, Object> schemaFieldDetails = schemaFieldDetails(schema.getJsonSchema());
        List<String> missingBefore = missingRequiredFields(schema.getJsonSchema(), safeJson(session.getContextJson()));
        Map<String, Object> missingOptionsBefore = missingFieldOptions(missingBefore, schemaFieldDetails);
        session.putInputParam("missing_fields", missingBefore);
        session.putInputParam("schema_field_details", schemaFieldDetails);
        session.putInputParam("missing_field_options", missingOptionsBefore);
        session.putInputParam("schema_description", schema.getDescription() == null ? "" : schema.getDescription());
        session.putInputParam("schema_id", schema.getSchemaId());
        ensureSchemaPromptVars(session);

        PromptTemplateContext promptTemplateContext = PromptTemplateContext
                .builder()
                .context(safeJson(session.getContextJson()))
                .userInput(session.getUserText())
                .schemaJson(schema.getJsonSchema())
                .conversationHistory(JsonUtil.toJson(session.conversionHistory()))
                .extra(session.getInputParams())
                .build();
        String systemPrompt = renderer.render(template.getSystemPrompt(), promptTemplateContext);
        String userPrompt = renderer.render(template.getUserPrompt(), promptTemplateContext);

        Map<String, Object> llmInputPayload = new LinkedHashMap<>();
        llmInputPayload.put("system_prompt", systemPrompt);
        llmInputPayload.put("user_prompt", userPrompt);
        llmInputPayload.put("schema", schema.getJsonSchema());
        llmInputPayload.put("userInput", session.getUserText());
        audit.audit("SCHEMA_EXTRACTION_LLM_INPUT", session.getConversationId(), llmInputPayload);

        LlmInvocationContext.set(session.getConversationId(), session.getIntent(), session.getState());

        String extractedJson = llm.generateJson(
                systemPrompt + "\n\n" + userPrompt,
                schema.getJsonSchema(),
                safeJson(session.getContextJson())
        );
        session.setLastLlmOutput(extractedJson);
        session.setLastLlmStage("SCHEMA_EXTRACTION");

        Map<String, Object> llmOutputPayload = new LinkedHashMap<>();
        llmOutputPayload.put("json", extractedJson);
        audit.audit("SCHEMA_EXTRACTION_LLM_OUTPUT", session.getConversationId(), llmOutputPayload);

        String merged = JsonUtil.merge(safeJson(session.getContextJson()), extractedJson);
        session.setContextJson(merged);
        session.getConversation().setContextJson(merged);

        boolean complete = JsonUtil.isSchemaComplete(schema.getJsonSchema(), merged);
        boolean hasAnySchemaValue = JsonUtil.hasAnySchemaValue(merged, schema.getJsonSchema());
        List<String> missingFields = missingRequiredFields(schema.getJsonSchema(), merged);
        Map<String, Object> missingFieldOptions = missingFieldOptions(missingFields, schemaFieldDetails);
        session.setSchemaComplete(complete);
        session.setSchemaHasAnyValue(hasAnySchemaValue);
        session.setMissingRequiredFields(missingFields);
        session.setMissingFieldOptions(missingFieldOptions);
        session.setResolvedSchema(schema);
        session.putInputParam("missing_fields", missingFields);
        session.putInputParam("schema_field_details", schemaFieldDetails);
        session.putInputParam("missing_field_options", missingFieldOptions);
        session.putInputParam("schema_description", schema.getDescription() == null ? "" : schema.getDescription());
        session.putInputParam("schema_id", schema.getSchemaId());
        ensureSchemaPromptVars(session);

        Map<String, Object> statusPayload = new LinkedHashMap<>();
        statusPayload.put("schemaComplete", complete);
        statusPayload.put("hasAnySchemaValue", hasAnySchemaValue);
        statusPayload.put("missingRequiredFields", missingFields);
        statusPayload.put("missingFieldOptions", missingFieldOptions);
        statusPayload.put("schemaId", schema.getSchemaId());
        statusPayload.put("intent", session.getIntent());
        statusPayload.put("state", session.getState());
        statusPayload.put("context", session.contextDict());
        statusPayload.put("extractedData", session.schemaExtractedDataDict());
        audit.audit("SCHEMA_STATUS", session.getConversationId(), statusPayload);
    }

    private CePromptTemplate resolvePromptTemplate(String responseType, String intentCode) {
        return promptTemplateRepo.findFirstByEnabledTrueAndResponseTypeAndIntentCodeOrderByCreatedAtDesc(responseType, intentCode)
                .orElseThrow(() -> new IllegalStateException("No enabled ce_prompt_template found for responseType=" + responseType));
    }

    private List<String> missingRequiredFields(String schemaJson, String contextJson) {
        try {
            JsonNode schema = JsonUtil.parseOrNull(schemaJson);
            JsonNode required = schema.path("required");
            JsonNode data = JsonUtil.parseOrNull(contextJson);
            List<String> missing = new ArrayList<>();
            if (!required.isArray()) {
                return missing;
            }
            required.forEach(req -> {
                String field = req.asText();
                JsonNode value = data.path(field);
                if (value.isMissingNode() || value.isNull()) {
                    missing.add(field);
                    return;
                }
                if (value.isTextual() && value.asText().isBlank()) {
                    missing.add(field);
                    return;
                }
                if (value.isArray() && value.isEmpty()) {
                    missing.add(field);
                }
            });
            return missing;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> schemaFieldDetails(String schemaJson) {
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            JsonNode schema = JsonUtil.parseOrNull(schemaJson);
            JsonNode properties = schema.path("properties");
            if (!properties.isObject()) {
                return details;
            }
            properties.fieldNames().forEachRemaining(name -> {
                JsonNode node = properties.path(name);
                Map<String, Object> fieldDetails = new LinkedHashMap<>();
                fieldDetails.put("description", node.path("description").isMissingNode() ? null : node.path("description").asText(null));
                fieldDetails.put("type", node.path("type").isMissingNode() ? null : node.path("type"));
                List<Object> enumValues = new ArrayList<>();
                JsonNode enumNode = node.path("enum");
                if (enumNode.isArray()) {
                    enumNode.forEach(v -> {
                        if (!v.isNull()) {
                            enumValues.add(v.isTextual() ? v.asText() : v.toString());
                        }
                    });
                }
                fieldDetails.put("enumOptions", enumValues);
                details.put(name, fieldDetails);
            });
            return details;
        } catch (Exception e) {
            return details;
        }
    }

    private Map<String, Object> missingFieldOptions(List<String> missingFields, Map<String, Object> fieldDetails) {
        Map<String, Object> options = new LinkedHashMap<>();
        for (String field : missingFields) {
            Object raw = fieldDetails.get(field);
            if (!(raw instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Object enumOptions = rawMap.get("enumOptions");
            if (enumOptions instanceof List<?> enumList && !enumList.isEmpty()) {
                options.put(field, enumList);
            }
        }
        return options;
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

    private void ensureSchemaPromptVars(EngineSession session) {
        if (session.getInputParams() == null) {
            return;
        }
        session.getInputParams().putIfAbsent("missing_fields", new ArrayList<>());
        session.getInputParams().putIfAbsent("schema_field_details", new LinkedHashMap<>());
        session.getInputParams().putIfAbsent("missing_field_options", new LinkedHashMap<>());
        session.getInputParams().putIfAbsent("schema_description", "");
    }
}
