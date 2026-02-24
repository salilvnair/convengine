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
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class AuditConversationHistoryProvider implements ConversationHistoryProvider {

    private static final String USER_ENTRY_TYPE = ConvEngineAuditStage.USER_INPUT.value();
    private static final List<String> AI_ENTRY_TYPES = List.of(
            "ASSISTANT_OUTPUT");

    private final com.github.salilvnair.convengine.service.ConversationHistoryCacheService historyCacheService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<ConversationTurn> lastTurns(UUID conversationId, int limit) {
        List<CeConversationHistory> historyRows = historyCacheService.getHistory(conversationId);

        List<ConversationTurn> turns = new ArrayList<>();
        for (CeConversationHistory row : historyRows) {
            String userInput = row.getUserInput();
            String assistantOutput = extractText(row.getAssistantOutput());
            if (userInput != null && !userInput.isBlank() && assistantOutput != null && !assistantOutput.isBlank()) {
                turns.add(new ConversationTurn(userInput, assistantOutput));
            }
        }
        return turns.size() > limit ? turns.subList(0, limit) : turns;
    }

    private String extractText(String payload) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }
        try {
            JsonNode node = mapper.readTree(payload);

            if (node.has("text"))
                return node.get("text").asText();
            if (node.has("value"))
                return node.get("value").asText();
            if (node.has("json"))
                return node.get("json").asText();
            if (node.has("output"))
                return node.get("output").asText();
            if (node.has("question"))
                return node.get("question").asText();
            if (node.has("data")) {
                JsonNode data = node.get("data");
                if (data.has("text"))
                    return data.get("text").asText();
                if (data.has("output"))
                    return data.get("output").asText();
                if (data.has("question"))
                    return data.get("question").asText();
            }

            return payload;
        } catch (Exception e) {
            return payload;
        }
    }
}
