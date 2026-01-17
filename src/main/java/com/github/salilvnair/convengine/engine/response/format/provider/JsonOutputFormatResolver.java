package com.github.salilvnair.convengine.engine.response.format.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.response.format.core.OutputFormatResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
            CeResponse response,
            CePromptTemplate template
    ) {
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
                        .build();

        String systemPrompt = renderer.render(template.getSystemPrompt(), ctx);
        String userPrompt = renderer.render(template.getUserPrompt(), ctx);

        LlmInvocationContext.set(
                session.getConversationId(),
                session.getIntent(),
                session.getState()
        );

        audit.audit(
                "RESOLVE_RESPONSE_LLM_INPUT",
                session.getConversationId(),
                "{\"system_prompt\":\"" + JsonUtil.escape(systemPrompt) +
                        "\",\"user_prompt\":\"" + JsonUtil.escape(userPrompt) +
                        "\",\"derivation_hint\":\"" + JsonUtil.escape(safe(response.getDerivationHint())) +
                        "\",\"schema\":\"" + JsonUtil.escape(response.getJsonSchema()) +
                        "\",\"context\":\"" + JsonUtil.escape(session.getContextJson()) + "\"}"
        );

        String json =
                llm.generateJson(
                        systemPrompt + "\n\n" + userPrompt + "\n\n" +
                                safe(response.getDerivationHint()),
                        response.getJsonSchema(),
                        session.getContextJson()
                );

        audit.audit(
                "RESOLVE_RESPONSE_LLM_OUTPUT",
                session.getConversationId(),
                "{\"json\":\"" + JsonUtil.escape(json) + "\"}"
        );

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
}
