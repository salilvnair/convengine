package com.github.salilvnair.convengine.engine.history.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.history.core.ConversationHistoryProvider;
import com.github.salilvnair.convengine.engine.history.model.ConversationTurn;
import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.repo.AuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AuditConversationHistoryProvider implements ConversationHistoryProvider {

    private static final String USER_STAGE = "USER_INPUT";
    private static final List<String> BOT_STAGES = List.of("ASSISTANT_OUTPUT", "RESOLVE_RESPONSE_LLM_OUTPUT", "INTENT_AGENT_LLM_OUTPUT");

    private final AuditRepository auditRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<ConversationTurn> lastTurns(UUID conversationId, int limit) {

        List<CeAudit> audits = auditRepo.findByConversationIdOrderByCreatedAtAsc(conversationId);

        List<ConversationTurn> turns = new ArrayList<>();

        String pendingUser = null;

        for (CeAudit audit : audits) {

            String stage = audit.getStage();
            String payload = audit.getPayloadJson();

            if (USER_STAGE.equals(stage)) {
                pendingUser = extractText(payload);
                continue;
            }

            if (BOT_STAGES.contains(stage) && pendingUser != null) {
                turns.add(
                        new ConversationTurn(
                                pendingUser,
                                extractText(payload)
                        )
                );
                pendingUser = null;
            }
        }

        // Return only last N turns
        int from = Math.max(0, turns.size() - limit);
        return turns.subList(from, turns.size());
    }

    private String extractText(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);

            if (node.has("text")) return node.get("text").asText();
            if (node.has("value")) return node.get("value").asText();
            if (node.has("json")) return node.get("json").asText();
            if (node.has("output")) return node.get("output").asText();
            if (node.has("question")) return node.get("question").asText();
            if (node.has("data")) {
                JsonNode data = node.get("data");
                if (data.has("text")) return data.get("text").asText();
                if (data.has("output")) return data.get("output").asText();
                if (data.has("question")) return data.get("question").asText();
            }

            return payload;
        } catch (Exception e) {
            return payload;
        }
    }
}
