package com.github.salilvnair.convengine.engine.history.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.history.core.ConversationHistoryProvider;
import com.github.salilvnair.convengine.engine.history.model.ConversationTurn;
import com.github.salilvnair.convengine.entity.CeConversationHistory;
import com.github.salilvnair.convengine.repo.ConversationHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class AuditConversationHistoryProvider implements ConversationHistoryProvider {

    private static final String USER_ENTRY_TYPE = ConvEngineAuditStage.USER_INPUT.value();
    private static final List<String> AI_ENTRY_TYPES = List.of(
            "ASSISTANT_OUTPUT"
    );

    private final ConversationHistoryRepository conversationHistoryRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<ConversationTurn> lastTurns(UUID conversationId, int limit) {
        Pageable pageable = PageRequest.of(0, 500);
        List<CeConversationHistory> historyRows = conversationHistoryRepository.findByConversationIdOrderByCreatedAtDesc(conversationId, pageable).getContent();;

        List<ConversationTurn> turns = new ArrayList<>();
        List<String> ALL_ENTRY_TYPES = Stream.concat(
                                                Stream.of(USER_ENTRY_TYPE),
                                                AI_ENTRY_TYPES.stream()
                                        ).toList();
        String userInput= null;
        String assistantOutput = null;
        List<CeConversationHistory> filteredHistoryRows = historyRows.stream().filter(row -> ALL_ENTRY_TYPES.contains(row.getEntryType())).toList();
        for (CeConversationHistory row : filteredHistoryRows) {
            String entryType = row.getEntryType();
            String payload = row.getPayloadJson();
            String contentText = row.getContentText();

            if (USER_ENTRY_TYPE.equals(entryType)) {
                userInput = firstNonBlank(contentText, extractText(payload));
            }
            if (AI_ENTRY_TYPES.contains(entryType)) {
                assistantOutput = firstNonBlank(contentText, extractText(payload));
            }
            if (userInput != null && assistantOutput != null) {
                ConversationTurn turn = new ConversationTurn(userInput, assistantOutput);
                turns.add(turn);
                userInput= null;
                assistantOutput = null;
            }
        }
        return turns.size() > limit ? turns.subList(0, limit): turns;
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
