package com.github.salilvnair.convengine.engine.response.format.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.response.format.core.OutputFormatResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.PromptTemplate;
import com.github.salilvnair.convengine.model.ResponseTemplate;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JsonOutputFormatResolver implements OutputFormatResolver {

    private final LlmClient llm;
    private final PromptTemplateRenderer renderer;
    private final AuditService audit;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String format() {
        return "JSON";
    }

    @Override
    public void resolve(
            EngineSession session,
            ResponseTemplate response,
            PromptTemplate template
    ) {
        ensurePromptInputs(session);
        session.putInputParam("session", session.sessionDict());
        session.putInputParam("context", session.contextDict());
        session.putInputParam("schema_extracted_data", session.schemaExtractedDataDict());

        String historyJson = JsonUtil.toJson(session.conversionHistory());

        PromptTemplateContext ctx =
                PromptTemplateContext.builder()
                        .context(session.getContextJson())
                        .userInput(session.getUserText())
                        .schemaJson(session.getResolvedSchema() != null
                                ? session.getResolvedSchema().getJsonSchema()
                                : null)
                        .containerDataJson(session.getContainerDataJson())
                        .validationJson(session.getValidationTablesJson())
                        .conversationHistory(historyJson)
                        .extra(session.getInputParams())
                        .build();

        String systemPrompt = renderer.render(template.getSystemPrompt(), ctx);
        String userPrompt = renderer.render(template.getUserPrompt(), ctx);

        LlmInvocationContext.set(
                session.getConversationId(),
                session.getIntent(),
                session.getState()
        );

        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("system_prompt", systemPrompt);
        inputPayload.put("user_prompt", userPrompt);
        inputPayload.put("derivation_hint", safe(response.getDerivationHint()));
        inputPayload.put("schema", response.getJsonSchema());
        inputPayload.put("context", session.getContextJson());
        audit.audit("RESOLVE_RESPONSE_LLM_INPUT", session.getConversationId(), inputPayload);

        String json =
                llm.generateJson(
                        systemPrompt + "\n\n" + userPrompt + "\n\n" +
                                safe(response.getDerivationHint()),
                        response.getJsonSchema(),
                        JsonUtil.toJson(session.contextDict())
                );
        session.setLastLlmOutput(json);
        session.setLastLlmStage("RESPONSE_JSON");

        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put("output", json);
        audit.audit("RESOLVE_RESPONSE_LLM_OUTPUT", session.getConversationId(), outputPayload);

        applyIntentAndStateOverride(json, session);

        session.setPayload(new JsonPayload(json));
        session.getConversation().setLastAssistantJson(json);
    }

    private void applyIntentAndStateOverride(String json, EngineSession session) {
        try {
            JsonNode node = mapper.readTree(json);

            if (node.hasNonNull("intent")) {
                session.setIntent(node.get("intent").asText());
                session.getConversation().setIntentCode(node.get("intent").asText());
            }

            if (node.hasNonNull("state")) {
                session.setState(node.get("state").asText());
                session.getConversation().setStateCode(node.get("state").asText());
            }
        } catch (Exception ignored) {}
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private void ensurePromptInputs(EngineSession session) {
        session.putInputParam("missing_fields", valueOrDefaultList(session.getInputParams().get("missing_fields")));
        session.putInputParam("missing_field_options", valueOrDefaultMap(session.getInputParams().get("missing_field_options")));
        session.putInputParam("schema_description", valueOrDefaultString(session.getInputParams().get("schema_description")));
        session.putInputParam("schema_field_details", valueOrDefaultMap(session.getInputParams().get("schema_field_details")));
        session.putInputParam("schema_id", session.getInputParams().getOrDefault("schema_id", null));
        session.putInputParam("context", session.contextDict());
        session.putInputParam("schema_extracted_data", session.schemaExtractedDataDict());
        session.putInputParam("session", session.sessionDict());
    }

    @SuppressWarnings("unchecked")
    private List<String> valueOrDefaultList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) out.add(String.valueOf(item));
            }
            return out;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> valueOrDefaultMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    private String valueOrDefaultString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
