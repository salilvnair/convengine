package com.github.salilvnair.convengine.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.steps.RulesStep;
import com.github.salilvnair.convengine.engine.type.RulePhase;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.repo.OutputSchemaRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class AgentIntentResolver implements IntentResolver {
    public static final String INTENT_COLLISION_STATE = "INTENT_COLLISION";
    private double MIN_CONFIDENCE;
    private double COLLISION_GAP_THRESHOLD;
    private String SYSTEM_PROMPT;
    private String USER_PROMPT;
    private final CeConfigResolver configResolver;
    private final AllowedIntentService allowedIntentService;
    private final LlmClient llm;
    private final AuditService audit;
    private final PromptTemplateRenderer renderer;
    private final OutputSchemaRepository outputSchemaRepo;
    private final RulesStep rulesStep;
    private final ObjectMapper mapper = new ObjectMapper();



    @PostConstruct
    public void init() {
        MIN_CONFIDENCE = configResolver.resolveDouble(this, "MIN_CONFIDENCE", 0.55d);
        COLLISION_GAP_THRESHOLD = configResolver.resolveDouble(this, "COLLISION_GAP_THRESHOLD", 0.1d);
        SYSTEM_PROMPT = configResolver.resolveString(this, "SYSTEM_PROMPT", """
                You are an intent resolution agent for a conversational engine.
                
                 Return JSON ONLY with fields:
                 {
                   "intent": "<INTENT_CODE_OR_NULL>",
                   "state": "INTENT_COLLISION | IDLE",
                   "confidence": 0.0,
                   "needsClarification": false,
                   "clarificationResolved": false,
                   "clarificationQuestion": "",
                   "intentScores": [{"intent":"<INTENT_CODE>","confidence":0.0}],
                   "followups": []
                 }
                
                 Rules:
                 - Score all plausible intents and return them in intentScores sorted by confidence descending.
                 - If top intents are close and ambiguous, set state to INTENT_COLLISION and needsClarification=true.
                 - For INTENT_COLLISION, add one follow-up disambiguation question in followups.
                 - If top intent is clear, set intent to best intent and confidence to best confidence.
                 - If user input is question-like (what/where/when/why/how/which/who/help/details/required/needed),
                   keep informational intents (like FAQ-style intents) in intentScores unless clearly impossible.
                 - When a domain/task intent and informational intent are both plausible for a question, keep both with close scores;
                   prefer INTENT_COLLISION instead of collapsing too early.
                 - Use only allowed intents.
                 - Do not hallucinate missing identifiers or facts.
                 - Keep state non-null when possible.
                
                """);
        USER_PROMPT = configResolver.resolveString(this, "USER_PROMPT", """
                
                Context:
                {{context}}
                
                Allowed intents:
                {{allowed_intents}}
                
                Potential intent collisions:
                {{intent_collision_candidates}}
                
                Current intent scores:
                {{intent_scores}}
                
                Previous clarification question (if any):
                {{pending_clarification}}
                
                User input:
                {{user_input}}
                
                Return JSON in the required schema only.
                
                """);
    }

    @Override
    public String resolve(EngineSession session) {
        session.putInputParam("agentResolver", true);
        String intent = _resolve(session);
        session.putInputParam("agentResolver", false);
        return intent;
    }

    private String _resolve(EngineSession session) {
        UUID conversationId = session.getConversationId();
        List<AllowedIntent> allowedIntents = allowedIntentService.allowedIntents();

        if (allowedIntents.isEmpty()) {
            audit.audit("INTENT_AGENT_SKIPPED", conversationId, Map.of("reason", "no allowed intents"));
            return null;
        }

        session.putInputParam("intent_collision_candidates", List.of());
        session.putInputParam("intent_scores", List.of());
        session.putInputParam("intent_top3", List.of());
        session.putInputParam("followups", List.of());

        String pendingClarification = session.hasPendingClarification()
                ? session.getPendingClarificationQuestion()
                : null;

        PromptTemplateContext promptTemplateContext =
                PromptTemplateContext.builder()
                        .context(session.getContextJson())
                        .userInput(session.getUserText())
                        .allowedIntents(allowedIntents)
                        .pendingClarification(pendingClarification)
                        .conversationHistory(JsonUtil.toJson(session.conversionHistory()))
                        .extra(session.promptTemplateVars())
                        .build();

        String systemPrompt = renderer.render(SYSTEM_PROMPT, promptTemplateContext);
        String userPrompt = renderer.render(USER_PROMPT, promptTemplateContext);

        Map<String, Object> llmInput = new LinkedHashMap<>();
        llmInput.put("templateFromCeConfig (AgentIntentResolver)", "USER_PROMPT, SYSTEM_PROMPT");
        llmInput.put("systemPrompt", systemPrompt);
        llmInput.put("userPrompt", userPrompt);
        audit.audit("INTENT_AGENT_LLM_INPUT", conversationId, llmInput);

        LlmInvocationContext.set(conversationId, session.getIntent(), session.getState());
        String output = llm.generateJson(
                systemPrompt + "\n\n" + userPrompt,
                null,
                session.getContextJson()
        );

        session.setLastLlmOutput(output);
        session.setLastLlmStage("INTENT_AGENT");
        session.setPayload(new JsonPayload(output));
        session.getConversation().setLastAssistantJson(output);
        audit.audit("INTENT_AGENT_LLM_OUTPUT", conversationId, Map.of("json", output));

        JsonNode node = parseNode(output);
        if (node == null || !node.isObject()) {
            audit.audit("INTENT_AGENT_REJECTED", conversationId, Map.of("reason", "invalid json"));
            return null;
        }

        String intent = text(node, "intent");
        String state = text(node, "state");
        double confidence = number(node, "confidence");
        boolean needsClarification = bool(node, "needsClarification");
        String clarificationQuestion = text(node, "clarificationQuestion");

        List<Map<String, Object>> scores = extractIntentScores(node, allowedIntents);
        List<String> followups = extractFollowups(node);
        session.putInputParam("intent_scores", scores);
        session.putInputParam("intent_top3", scores.stream().limit(3).toList());
        session.putInputParam("followups", followups);
        audit.audit("INTENT_AGENT_SCORES", conversationId, Map.of("scores", scores, "followups", followups));

        if ((intent == null || intent.isBlank()) && !scores.isEmpty()) {
            intent = str(scores.getFirst().get("intent"));
            confidence = Math.max(confidence, number(scores.getFirst().get("confidence")));
        }

        List<Map<String, Object>> collisionCandidates = collisionCandidates(scores);
        session.putInputParam("intent_collision_candidates", collisionCandidates);

        if (!collisionCandidates.isEmpty() && !session.hasPendingClarification() && isCollision(scores)) {
            needsClarification = true;
            state = INTENT_COLLISION_STATE;
            if (intent == null || intent.isBlank()) {
                intent = str(collisionCandidates.getFirst().get("code"));
            }
            if (clarificationQuestion == null || clarificationQuestion.isBlank()) {
                clarificationQuestion = !followups.isEmpty()
                        ? followups.getFirst()
                        : buildCollisionQuestion(collisionCandidates);
            }
            Map<String, Object> collisionPayload = new LinkedHashMap<>();
            collisionPayload.put("intent", intent);
            collisionPayload.put("state", state);
            collisionPayload.put("question", clarificationQuestion);
            collisionPayload.put("candidates", collisionCandidates);
            collisionPayload.put("scores", scores.stream().limit(3).toList());
            audit.audit("INTENT_AGENT_COLLISION", conversationId, collisionPayload);
        }

        if (needsClarification) {
            if (isSchemaDrivenIntent(intent)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("intent", intent);
                payload.put("state", state);
                payload.put("question", clarificationQuestion);
                audit.audit("INTENT_AGENT_CLARIFICATION_SUPPRESSED_SCHEMA_FLOW", conversationId, payload);
                needsClarification = false;
                clarificationQuestion = null;
            }
        }

        // Treat followups as valid clarification questions when the model flags clarification/collision.
        if ((needsClarification || INTENT_COLLISION_STATE.equalsIgnoreCase(state))
                && (clarificationQuestion == null || clarificationQuestion.isBlank())
                && !followups.isEmpty()) {
            clarificationQuestion = followups.getFirst();
            needsClarification = true;
        }

        if (needsClarification) {
            if (clarificationQuestion == null || clarificationQuestion.isBlank()) {
                audit.audit("INTENT_AGENT_REJECTED", conversationId,
                        Map.of("reason", "needsClarification without question"));
                return null;
            }

            session.setPendingClarificationQuestion(clarificationQuestion);
            session.setPendingClarificationReason("AGENT");
            session.addClarificationHistory();
            if (intent != null && !intent.isBlank()) {
                session.setIntent(intent);
            }
            if (state != null && !state.isBlank()) {
                session.setState(state);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", session.getIntent());
            payload.put("state", session.getState());
            payload.put("question", clarificationQuestion);
            payload.put("followups", session.getInputParams().get("followups"));
            audit.audit("INTENT_AGENT_NEEDS_CLARIFICATION", conversationId, payload);
            return session.getIntent();
        }

        if (intent == null || intent.isBlank()) {
            audit.audit("INTENT_AGENT_REJECTED", conversationId, Map.of("reason", "Intent could not be determined with sufficient confidence, allowed intents and scores", "allowedIntents", allowedIntents, "scores", scores));
            return null;
        }

        if (!allowedIntentService.isAllowed(intent)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", intent);
            payload.put("state", state);
            payload.put("reason", "Intent not in allowed intents");
            audit.audit("INTENT_AGENT_REJECTED", conversationId, payload);
            return null;
        }

        if (confidence < MIN_CONFIDENCE) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", intent);
            payload.put("state", state);
            payload.put("confidence", confidence);
            audit.audit("INTENT_AGENT_REJECTED", conversationId, payload);
            return null;
        }

        session.setIntent(intent);
        if (state != null && !state.isBlank()) {
            session.setState(state);
        }
        applyPostIntentRules(session);

        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("intent", session.getIntent());
        accepted.put("state", session.getState());
        accepted.put("confidence", confidence);
        accepted.put("scores", scores.stream().limit(3).toList());
        session.setPendingClarificationQuestion(clarificationQuestion);
        audit.audit("INTENT_AGENT_ACCEPTED", conversationId, accepted);
        return session.getIntent();
    }

    private void applyPostIntentRules(EngineSession session) {
        if (session.getIntent() == null || session.getIntent().isBlank()) {
            return;
        }
        session.setRuleExecutionSource("AgentIntentResolver PostIntent");
        session.setRuleExecutionOrigin("AGENT_INTENT_RESOLVER");
        session.putInputParam("rule_execution_source", "AgentIntentResolver PostIntent");
        session.putInputParam("rule_execution_origin", "AGENT_INTENT_RESOLVER");
        rulesStep.applyRules(session, "AgentIntentResolver PostIntent", RulePhase.AGENT_POST_INTENT.name());
    }

    private boolean isSchemaDrivenIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return false;
        }
        try {
            return outputSchemaRepo.findAll().stream()
                    .anyMatch(s -> Boolean.TRUE.equals(s.getEnabled())
                            && s.getIntentCode() != null
                            && s.getIntentCode().equalsIgnoreCase(intent));
        } catch (Exception e) {
            return false;
        }
    }

    private List<Map<String, Object>> extractIntentScores(JsonNode node, List<AllowedIntent> allowedIntents) {
        Set<String> allowedCodes = new LinkedHashSet<>();
        for (AllowedIntent allowedIntent : allowedIntents) {
            if (allowedIntent.code() != null) {
                allowedCodes.add(allowedIntent.code().trim().toUpperCase());
            }
        }

        List<Map<String, Object>> scores = new ArrayList<>();
        JsonNode scoreNode = node.path("intentScores");
        if (scoreNode.isMissingNode()) {
            scoreNode = node.path("intent_scores");
        }

        if (scoreNode.isObject()) {
            scoreNode.fields().forEachRemaining(entry -> {
                String code = str(entry.getKey());
                double confidence = number(entry.getValue());
                if (code != null && allowedCodes.contains(code.toUpperCase())) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("intent", code);
                    row.put("confidence", clamp01(confidence));
                    scores.add(row);
                }
            });
        }

        if (scoreNode.isArray()) {
            for (JsonNode item : scoreNode) {
                String code = text(item, "intent");
                if (code == null || code.isBlank()) {
                    code = text(item, "code");
                }
                if (code == null || code.isBlank() || !allowedCodes.contains(code.toUpperCase())) {
                    continue;
                }
                double confidence = number(item, "confidence");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("intent", code);
                row.put("confidence", clamp01(confidence));
                scores.add(row);
            }
        }

        scores.sort((a, b) -> {
            int byConfidence = Double.compare(
                    number(b.get("confidence")),
                    number(a.get("confidence"))
            );
            if (byConfidence != 0) return byConfidence;
            return str(a.get("intent")).compareToIgnoreCase(str(b.get("intent")));
        });
        return scores;
    }

    private List<String> extractFollowups(JsonNode node) {
        JsonNode followups = node.path("followups");
        if (followups.isMissingNode()) {
            followups = node.path("follow_ups");
        }
        List<String> out = new ArrayList<>();
        if (followups.isTextual()) {
            String text = followups.asText("").trim();
            if (!text.isBlank()) out.add(text);
            return out;
        }
        if (!followups.isArray()) {
            return out;
        }
        for (JsonNode item : followups) {
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out;
    }

    private List<Map<String, Object>> collisionCandidates(List<Map<String, Object>> scores) {
        List<Map<String, Object>> out = new ArrayList<>();
        int limit = Math.min(3, scores.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> src = scores.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", src.get("intent"));
            row.put("confidence", src.get("confidence"));
            out.add(row);
        }
        return out;
    }

    private boolean isCollision(List<Map<String, Object>> scores) {
        if (scores.size() < 2) {
            return false;
        }
        double first = number(scores.get(0).get("confidence"));
        double second = number(scores.get(1).get("confidence"));
        return (first - second) <= COLLISION_GAP_THRESHOLD;
    }

    private String buildCollisionQuestion(List<Map<String, Object>> candidates) {
        if (candidates.size() < 2) {
            return "Can you clarify which intent you meant?";
        }
        String first = labelForCode(str(candidates.get(0).get("code")));
        String second = labelForCode(str(candidates.get(1).get("code")));
        return "Did you mean " + first + " or " + second + "?";
    }

    private String labelForCode(String code) {
        if (code == null || code.isBlank()) return "that request";
        String[] parts = code.toLowerCase().replace("_", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1));
        }
        return sb.toString();
    }

    private JsonNode parseNode(String output) {
        try {
            return mapper.readTree(output);
        } catch (Exception e) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null) return null;
        JsonNode child = node.path(field);
        return child.isTextual() ? child.asText() : null;
    }

    private boolean bool(JsonNode node, String field) {
        if (node == null || field == null) return false;
        return node.path(field).asBoolean(false);
    }

    private double number(JsonNode node, String field) {
        if (node == null || field == null) return 0.0d;
        return node.path(field).asDouble(0.0d);
    }

    private double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(str(value));
        } catch (Exception e) {
            return 0.0d;
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private double clamp01(double x) {
        if (x < 0.0d) return 0.0d;
        return Math.min(x, 1.0d);
    }
}
