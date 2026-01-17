package com.github.salilvnair.convengine.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeOutputSchema;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.repo.OutputSchemaRepository;
import com.github.salilvnair.convengine.repo.PromptTemplateRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
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

        UUID conversationId = session.getConversationId();
        List<AllowedIntent> allowedIntents = allowedIntentService.allowedIntents();

        if (allowedIntents.isEmpty()) {
            audit.audit(
                    "INTENT_AGENT_SKIPPED",
                    conversationId,
                    "{\"reason\":\"no allowed intents configured\"}"
            );
            return null;
        }

        // -------------------------------------------------
        // Load INTENT_AGENT prompt template
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

        String pendingClarification =
                session.hasPendingClarification()
                        ? session.getPendingClarificationQuestion()
                        : null;

        PromptTemplateContext promptTemplateContext =
                PromptTemplateContext.builder()
                        .context(session.getContextJson())
                        .userInput(session.getUserText())
                        .schemaJson(
                                session.getResolvedSchema() != null
                                        ? session.getResolvedSchema().getJsonSchema()
                                        : null
                        )
                        .allowedIntents(allowedIntents)
                        .pendingClarification(pendingClarification)
                        .build();

        String systemPrompt = renderer.render(template.getSystemPrompt(), promptTemplateContext);
        String userPrompt = renderer.render(template.getUserPrompt(), promptTemplateContext);

        // -------------------------------------------------
        // Resolve output schema (STRICT vs NON-STRICT)
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
                            "type": "object",
                            "required": [
                              "intent",
                              "confidence",
                              "needsClarification",
                              "clarificationResolved",
                              "clarificationQuestion"
                            ],
                            "properties": {
                              "intent": { "type": ["string", "null"] },
                              "confidence": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
                              "needsClarification": { "type": "boolean" },
                              "clarificationResolved": { "type": "boolean" },
                              "clarificationQuestion": { "type": ["string", "null"] }
                            },
                            "additionalProperties": false
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
        session.setPayload(new JsonPayload(output));
        session.getConversation().setLastAssistantJson(output);
        audit.audit(
                "INTENT_AGENT_LLM_OUTPUT",
                conversationId,
                "{\"json\":\"" + JsonUtil.escape(output) + "\"}"
        );

        // -------------------------------------------------
        // Parse result
        // -------------------------------------------------
        IntentAgentResult result = parse(output);
        if (result == null) {
            audit.audit(
                    "INTENT_AGENT_REJECTED",
                    conversationId,
                    "{\"reason\":\"invalid json output\"}"
            );
            return null;
        }

        // -------------------------------------------------
        // Guard: clarificationResolved without pending clarification
        // -------------------------------------------------
        if (result.clarificationResolved() && !session.hasPendingClarification()) {
            audit.audit(
                    "INTENT_AGENT_REJECTED",
                    conversationId,
                    "{\"reason\":\"clarificationResolved=true but no pending clarification\"}"
            );
            return null;
        }

        // -------------------------------------------------
        // PATH A — agent asks clarification
        // -------------------------------------------------
        if (result.needsClarification()) {
            String question = safe(result.clarificationQuestion());
            if (question.isBlank()) {
                audit.audit(
                        "INTENT_AGENT_REJECTED",
                        conversationId,
                        "{\"reason\":\"needsClarification=true but clarificationQuestion empty\"}"
                );
                return null;
            }

            session.setPendingClarificationQuestion(question);

            audit.audit(
                    "INTENT_AGENT_NEEDS_CLARIFICATION",
                    conversationId,
                    "{\"question\":\"" + JsonUtil.escape(question) + "\"}"
            );

            return null;
        }

        // -------------------------------------------------
        // PATH B — clarification resolved
        // -------------------------------------------------
        if (session.hasPendingClarification() && result.clarificationResolved()) {

            String resolvedIntent = safe(result.intent());
            if (resolvedIntent.isBlank()) {
                audit.audit(
                        "INTENT_AGENT_REJECTED",
                        conversationId,
                        "{\"reason\":\"clarificationResolved=true but intent missing\"}"
                );
                return null;
            }

            session.clearClarification();

            audit.audit(
                    "INTENT_AGENT_CLARIFICATION_RESOLVED",
                    conversationId,
                    "{\"intent\":\"" + JsonUtil.escape(resolvedIntent) + "\"}"
            );

            return resolvedIntent;
        }

        // -------------------------------------------------
        // PATH C — normal intent resolution
        // -------------------------------------------------
        String intent = safe(result.intent());
        double confidence = result.confidence();

        if (intent.isBlank()) {
            audit.audit(
                    "INTENT_AGENT_REJECTED",
                    conversationId,
                    "{\"reason\":\"intent blank\"}"
            );
            return null;
        }

        if (!allowedIntentService.isAllowed(intent)) {
            audit.audit(
                    "INTENT_AGENT_REJECTED",
                    conversationId,
                    "{\"intent\":\"" + JsonUtil.escape(intent) +
                            "\",\"reason\":\"not allowed\"}"
            );
            return null;
        }

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
    // JSON parsing
    // -------------------------------------------------
    private IntentAgentResult parse(String json) {
        try {
            JsonNode node = mapper.readTree(json);

            return new IntentAgentResult(
                    node.path("intent").isTextual() ? node.path("intent").asText() : null,
                    node.path("confidence").asDouble(0.0),
                    node.path("needsClarification").asBoolean(false),
                    node.path("clarificationQuestion").isTextual()
                            ? node.path("clarificationQuestion").asText()
                            : null,
                    node.path("clarificationResolved").asBoolean(false)
            );
        } catch (Exception e) {
            return null;
        }
    }

    private String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
