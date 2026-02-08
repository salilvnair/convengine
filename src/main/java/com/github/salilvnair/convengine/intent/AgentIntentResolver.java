package com.github.salilvnair.convengine.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import com.github.salilvnair.convengine.engine.rule.action.factory.RuleTypeResolverFactory;
import com.github.salilvnair.convengine.engine.rule.type.core.RuleTypeResolver;
import com.github.salilvnair.convengine.engine.rule.type.factory.RuleActionResolverFactory;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeRule;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.repo.PromptTemplateRepository;
import com.github.salilvnair.convengine.repo.RuleRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
@Component
public class AgentIntentResolver implements IntentResolver {

    public static final String PURPOSE_INTENT_AGENT = "INTENT_AGENT";
    private static final String INTENT_COLLISION_STATE = "INTENT_COLLISION";
    private static final double MIN_CONFIDENCE = 0.55d;
    private static final double COLLISION_GAP_THRESHOLD = 0.1d;

    private final PromptTemplateRepository promptTemplateRepo;
    private final AllowedIntentService allowedIntentService;
    private final LlmClient llm;
    private final AuditService audit;
    private final PromptTemplateRenderer renderer;
    private final RuleRepository ruleRepo;
    private final RuleTypeResolverFactory ruleTypeFactory;
    private final RuleActionResolverFactory actionFactory;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String resolve(EngineSession session) {
        UUID conversationId = session.getConversationId();
        List<AllowedIntent> allowedIntents = allowedIntentService.allowedIntents();

        if (allowedIntents.isEmpty()) {
            audit.audit("INTENT_AGENT_SKIPPED", conversationId, Map.of("reason", "no allowed intents"));
            return null;
        }

        CePromptTemplate template = promptTemplateRepo
                .findFirstByEnabledTrueAndPurposeAndIntentCodeIsNullOrderByCreatedAtDesc(PURPOSE_INTENT_AGENT)
                .orElseThrow(() -> new IllegalStateException("Missing ce_prompt_template for INTENT_AGENT"));

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
                        .extra(session.getInputParams())
                        .build();

        String systemPrompt = renderer.render(template.getSystemPrompt(), promptTemplateContext);
        String userPrompt = renderer.render(template.getUserPrompt(), promptTemplateContext);

        Map<String, Object> llmInput = new LinkedHashMap<>();
        llmInput.put("templateId", template.getTemplateId());
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
            intent = str(scores.get(0).get("intent"));
            confidence = Math.max(confidence, number(scores.get(0).get("confidence")));
        }

        List<Map<String, Object>> collisionCandidates = collisionCandidates(scores);
        session.putInputParam("intent_collision_candidates", collisionCandidates);

        if (!collisionCandidates.isEmpty() && !session.hasPendingClarification() && isCollision(scores)) {
            needsClarification = true;
            state = INTENT_COLLISION_STATE;
            if (intent == null || intent.isBlank()) {
                intent = str(collisionCandidates.get(0).get("code"));
            }
            if (clarificationQuestion == null || clarificationQuestion.isBlank()) {
                clarificationQuestion = !followups.isEmpty()
                        ? followups.get(0)
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
            if (clarificationQuestion == null || clarificationQuestion.isBlank()) {
                audit.audit("INTENT_AGENT_REJECTED", conversationId,
                        Map.of("reason", "needsClarification without question"));
                return null;
            }

            session.setPendingClarificationQuestion(clarificationQuestion);
            session.setPendingClarificationReason("AGENT");
            if (intent != null && !intent.isBlank()) {
                session.setIntent(intent);
            }
            if (state != null && !state.isBlank()) {
                session.setState(state);
            }

            applyPostIntentRules(session);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", session.getIntent());
            payload.put("state", session.getState());
            payload.put("question", clarificationQuestion);
            payload.put("followups", session.getInputParams().get("followups"));
            audit.audit("INTENT_AGENT_NEEDS_CLARIFICATION", conversationId, payload);
            return session.getIntent();
        }

        if (intent == null || intent.isBlank()) {
            audit.audit("INTENT_AGENT_REJECTED", conversationId, Map.of("reason", "blank intent"));
            return null;
        }

        if (!allowedIntentService.isAllowed(intent)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", intent);
            payload.put("state", state);
            payload.put("reason", "not allowed");
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
        audit.audit("INTENT_AGENT_ACCEPTED", conversationId, accepted);
        return session.getIntent();
    }

    private void applyPostIntentRules(EngineSession session) {
        if (session.getIntent() == null || session.getIntent().isBlank()) {
            return;
        }

        List<CeRule> allRules = ruleRepo.findByEnabledTrueOrderByPriorityAsc();
        int maxPasses = 5;
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean passChanged = false;

            for (CeRule rule : allRules) {
                if (rule.getIntentCode() != null &&
                        !rule.getIntentCode().equalsIgnoreCase(session.getIntent())) {
                    continue;
                }

                RuleTypeResolver typeResolver = ruleTypeFactory.get(rule.getRuleType());
                if (typeResolver == null || !typeResolver.resolve(session, rule)) {
                    continue;
                }

                if ("RESOLVE_INTENT".equalsIgnoreCase(rule.getAction())) {
                    continue;
                }

                Map<String, Object> matchedPayload = new LinkedHashMap<>();
                matchedPayload.put("ruleId", rule.getRuleId());
                matchedPayload.put("action", rule.getAction());
                matchedPayload.put("ruleType", rule.getRuleType());
                matchedPayload.put("intent", session.getIntent());
                matchedPayload.put("state", session.getState());
                audit.audit("POST_INTENT_RULE_MATCHED", session.getConversationId(), matchedPayload);

                String previousIntent = session.getIntent();
                String previousState = session.getState();

                RuleActionResolver actionResolver = actionFactory.get(rule.getAction());
                if (actionResolver != null) {
                    actionResolver.resolve(session, rule);
                }

                if (!Objects.equals(previousIntent, session.getIntent()) ||
                        !Objects.equals(previousState, session.getState())) {
                    passChanged = true;
                }
            }

            if (!passChanged) {
                break;
            }
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
