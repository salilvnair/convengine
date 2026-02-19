package com.github.salilvnair.convengine.engine.history.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.history.core.ConversationHistoryProvider;
import com.github.salilvnair.convengine.engine.history.model.ConversationTurn;
import com.github.salilvnair.convengine.entity.CeConversationHistory;
import com.github.salilvnair.convengine.repo.ConversationHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AuditConversationHistoryProvider implements ConversationHistoryProvider {

    private static final String USER_ENTRY_TYPE = ConvEngineAuditStage.USER_INPUT.value();
    private static final List<String> AI_ENTRY_TYPES = List.of(
            "INTENT_AI_RESPONSE",
            "MCP_AI_RESPONSE",
            "RESOLVE_RESPONSE_AI_RESPONSE",
            "AI_RESPONSE"
    );

    private final ConversationHistoryRepository conversationHistoryRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<ConversationTurn> lastTurns(UUID conversationId, int limit) {
        List<CeConversationHistory> historyRows = conversationHistoryRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);

        List<ConversationTurn> turns = new ArrayList<>();

        String pendingUser = null;

        for (CeConversationHistory row : historyRows) {

            String entryType = row.getEntryType();
            String payload = row.getPayloadJson();
            String contentText = row.getContentText();

            if (USER_ENTRY_TYPE.equals(entryType)) {
                pendingUser = firstNonBlank(contentText, extractText(payload));
                continue;
            }

            if (AI_ENTRY_TYPES.contains(entryType) && pendingUser != null) {
                turns.add(
                        new ConversationTurn(
                                pendingUser,
                                firstNonBlank(contentText, extractText(payload))
                        )
                );
                pendingUser = null;
            }
        }

        // Return only last N turns
        int from = Math.max(0, turns.size() - limit);
        return turns.subList(from, turns.size());
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
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
