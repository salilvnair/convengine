package com.github.salilvnair.convengine.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
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

import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class AgentIntentResolver implements IntentResolver {

    public static final String PURPOSE_INTENT_AGENT = "INTENT_AGENT";

    private final PromptTemplateRepository promptTemplateRepo;
    private final OutputSchemaRepository outputSchemaRepo;
    private final AllowedIntentService allowedIntentService;
    private final LlmClient llm;
    private final AuditService audit;
    private final PromptTemplateRenderer renderer;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String resolve(EngineSession session) {

        // 🔒 Only classify at IDLE
        if (!"IDLE".equalsIgnoreCase(session.getState())) {
            return null;
        }

        UUID conversationId = session.getConversationId();
        Set<String> allowedIntents = allowedIntentService.allowedIntentCodes();

        if (allowedIntents.isEmpty()) {
            audit.audit(
                    "INTENT_AGENT_SKIPPED",
                    conversationId,
                    "{\"reason\":\"no allowed intents configured\"}"
            );
            return null;
        }

        // -------------------------------------------------
        // Load prompt template (INTENT_AGENT)
        // -------------------------------------------------
        CePromptTemplate template =
                promptTemplateRepo
                        .findFirstByEnabledTrueAndPurposeAndIntentCodeIsNullOrderByCreatedAtDesc(
                                PURPOSE_INTENT_AGENT
                        )
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Missing ce_prompt_template for purpose=INTENT_AGENT"
                                )
                        );
        PromptTemplateContext promptTemplateContext = PromptTemplateContext
                                                        .builder()
                                                        .context(session.getContextJson())
                                                        .userInput(session.getUserText())
                                                        .schemaJson(session.getResolvedSchema() != null ? session.getResolvedSchema().getJsonSchema() : null)
                                                        .allowedIntents(allowedIntents)
                                                        .build();
        String systemPrompt = renderer.render(template.getSystemPrompt(), promptTemplateContext);
        String userPrompt = renderer.render(template.getUserPrompt(), promptTemplateContext);

        // -------------------------------------------------
        // Decide STRICT vs NON-STRICT JSON (CRITICAL)
        // -------------------------------------------------
        CeOutputSchema schemaEntity =
                outputSchemaRepo
                        .findFirstByEnabledTrueAndIntentCodeAndStateCodeOrderByPriorityAsc(
                                PURPOSE_INTENT_AGENT,
                                session.getState()
                        )
                        .orElse(null);

        boolean strictJson = (schemaEntity != null);
        String jsonSchema =
                strictJson
                        ? schemaEntity.getJsonSchema()
                        : """
                          {
                            "type":"object",
                            "required":["intent","confidence"],
                            "properties":{
                              "intent":{"type":"string"},
                              "confidence":{"type":"number"}
                            }
                          }
                          """;

        audit.audit(
                "INTENT_AGENT_LLM_INPUT",
                conversationId,
                "{\"templateId\":\"" + template.getTemplateId() +
                        "\",\"strict\":" + strictJson +
                        ",\"system_prompt\":\"" + JsonUtil.escape(systemPrompt) +
                        "\",\"user_prompt\":\"" + JsonUtil.escape(userPrompt) +
                        "\",\"schema\":\"" + JsonUtil.escape(jsonSchema) + "\"}"
        );

        // -------------------------------------------------
        // Invoke LLM
        // -------------------------------------------------
        LlmInvocationContext.set(
                conversationId,
                session.getIntent(),
                session.getState()
        );

        String output =
                strictJson
                        ? llm.generateJsonStrict(
                        systemPrompt + "\n\n" + userPrompt,
                        jsonSchema,
                        session.getContextJson()
                )
                        : llm.generateJson(
                        systemPrompt + "\n\n" + userPrompt,
                        jsonSchema,
                        session.getContextJson()
                );

        audit.audit(
                "INTENT_AGENT_LLM_OUTPUT",
                conversationId,
                "{\"json\":\"" + JsonUtil.escape(output) + "\"}"
        );

        // -------------------------------------------------
        // Parse + validate
        // -------------------------------------------------
        IntentAgentResult result = parse(output);
        if (result == null || result.intent() == null) {
            audit.audit(
                    "INTENT_AGENT_REJECTED",
                    conversationId,
                    "{\"reason\":\"invalid json output\"}"
            );
            return null;
        }

        String intent = result.intent().trim();
        double confidence = result.confidence();

        // Hard gate #1 — allowed intents
        if (!allowedIntentService.isAllowed(intent)) {
            audit.audit(
                    "INTENT_AGENT_REJECTED",
                    conversationId,
                    "{\"intent\":\"" + JsonUtil.escape(intent) +
                            "\",\"reason\":\"not allowed\"}"
            );
            return null;
        }

        // Hard gate #2 — confidence
        if (confidence < 0.55) {
            audit.audit(
                    "INTENT_AGENT_REJECTED",
                    conversationId,
                    "{\"intent\":\"" + JsonUtil.escape(intent) +
                            "\",\"confidence\":" + confidence +
                            ",\"reason\":\"low confidence\"}"
            );
            return null;
        }

        audit.audit(
                "INTENT_AGENT_ACCEPTED",
                conversationId,
                "{\"intent\":\"" + JsonUtil.escape(intent) +
                        "\",\"confidence\":" + confidence + "}"
        );

        return intent;
    }

    // -------------------------------------------------
    // JSON parse helper
    // -------------------------------------------------
    private IntentAgentResult parse(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            String intent =
                    node.path("intent").isTextual()
                            ? node.path("intent").asText()
                            : null;
            double confidence =
                    node.path("confidence").isNumber()
                            ? node.path("confidence").asDouble()
                            : 0.0;
            return new IntentAgentResult(intent, confidence);
        } catch (Exception e) {
            return null;
        }
    }
}
