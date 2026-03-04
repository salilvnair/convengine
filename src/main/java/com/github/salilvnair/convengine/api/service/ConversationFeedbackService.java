package com.github.salilvnair.convengine.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.api.dto.ConversationFeedbackRequest;
import com.github.salilvnair.convengine.api.dto.ConversationFeedbackResponse;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.mcp.McpConstants;
import com.github.salilvnair.convengine.engine.mcp.knowledge.SemanticCatalogConstants;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.entity.CeMcpUserFeedback;
import com.github.salilvnair.convengine.entity.CeMcpUserQueryKnowledge;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import com.github.salilvnair.convengine.repo.McpUserFeedbackRepository;
import com.github.salilvnair.convengine.repo.McpUserQueryKnowledgeRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ConversationFeedbackService {

    private static final String FEEDBACK_THUMBS_UP = "THUMBS_UP";
    private static final String FEEDBACK_THUMBS_DOWN = "THUMBS_DOWN";
    private static final String LEGACY_KNOWLEDGE_TOOL_CODE = "db.knowledge.graph";

    private final ConversationRepository conversationRepository;
    private final McpUserFeedbackRepository feedbackRepository;
    private final McpUserQueryKnowledgeRepository userQueryKnowledgeRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ConversationFeedbackResponse submit(ConversationFeedbackRequest request) {
        if (request == null || request.getConversationId() == null) {
            throw new IllegalArgumentException("conversationId is required.");
        }

        String normalizedFeedback = normalizeFeedbackType(request.getFeedbackType());
        CeConversation conversation = conversationRepository.findById(request.getConversationId())
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found for feedback."));

        String assistantResponse = resolveAssistantResponse(request, conversation);
        List<Map<String, Object>> capturedQueryKnowledge = extractQueryKnowledgeFromContext(conversation.getContextJson());

        CeMcpUserFeedback feedback = CeMcpUserFeedback.builder()
                .conversationId(conversation.getConversationId())
                .feedbackType(normalizedFeedback)
                .messageId(trimToNull(request.getMessageId()))
                .intentCode(trimToNull(conversation.getIntentCode()))
                .stateCode(trimToNull(conversation.getStateCode()))
                .userQuery(trimToNull(conversation.getLastUserText()))
                .assistantResponse(assistantResponse)
                .mcpToolCode(resolveLastMcpToolCode(conversation.getContextJson()))
                .capturedQueryKnowledgeCount(capturedQueryKnowledge.size())
                .appliedQueryKnowledgeJson(JsonUtil.toJson(capturedQueryKnowledge))
                .metadataJson(JsonUtil.toJson(request.getMetadata() == null ? Map.of() : request.getMetadata()))
                .build();
        feedback = feedbackRepository.save(feedback);

        persistUserQueryKnowledge(capturedQueryKnowledge);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("conversation_id", String.valueOf(conversation.getConversationId()));
        auditPayload.put("feedback_type", normalizedFeedback);
        auditPayload.put("message_id", trimToNull(request.getMessageId()));
        auditPayload.put("captured_query_knowledge_count", capturedQueryKnowledge.size());
        auditPayload.put("feedback_id", feedback.getFeedbackId());
        auditService.audit(ConvEngineAuditStage.MCP_USER_FEEDBACK, conversation.getConversationId(), auditPayload);

        return ConversationFeedbackResponse.builder()
                .success(true)
                .feedbackId(feedback.getFeedbackId())
                .capturedQueryKnowledgeCount(capturedQueryKnowledge.size())
                .message("Feedback saved.")
                .build();
    }

    private void persistUserQueryKnowledge(List<Map<String, Object>> queryKnowledgeRows) {
        if (queryKnowledgeRows == null || queryKnowledgeRows.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : queryKnowledgeRows) {
            String queryText = trimToNull(asText(row.get(SemanticCatalogConstants.KEY_QUERY_TEXT)));
            if (queryText == null) {
                continue;
            }
            CeMcpUserQueryKnowledge knowledge = CeMcpUserQueryKnowledge.builder()
                    .queryText(queryText)
                    .description(trimToNull(asText(row.get(SemanticCatalogConstants.KEY_DESCRIPTION))))
                    .preparedSql(trimToNull(asText(row.get(SemanticCatalogConstants.KEY_PREPARED_SQL))))
                    .tags(toCsv(row.get(SemanticCatalogConstants.KEY_TAGS)))
                    .apiHints(toCsv(row.get(SemanticCatalogConstants.KEY_API_HINTS)))
                    .embedding(null)
                    .build();
            userQueryKnowledgeRepository.save(knowledge);
        }
    }

    private String resolveAssistantResponse(ConversationFeedbackRequest request, CeConversation conversation) {
        String fromRequest = trimToNull(request.getAssistantResponse());
        if (fromRequest != null) {
            return fromRequest;
        }
        String raw = trimToNull(conversation.getLastAssistantJson());
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isObject()) {
                JsonNode value = node.path("value");
                if (value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
        } catch (Exception ignored) {
        }
        return raw;
    }

    private String normalizeFeedbackType(String feedbackType) {
        String normalized = trimToNull(feedbackType);
        if (normalized == null) {
            throw new IllegalArgumentException("feedbackType is required.");
        }
        String value = normalized.trim().toUpperCase(Locale.ROOT);
        if (!FEEDBACK_THUMBS_UP.equals(value) && !FEEDBACK_THUMBS_DOWN.equals(value)) {
            throw new IllegalArgumentException("feedbackType must be THUMBS_UP or THUMBS_DOWN.");
        }
        return value;
    }

    private String resolveLastMcpToolCode(String contextJson) {
        try {
            JsonNode observations = mcpObservations(contextJson);
            if (observations == null || !observations.isArray() || observations.isEmpty()) {
                return null;
            }
            for (int i = observations.size() - 1; i >= 0; i--) {
                JsonNode node = observations.get(i);
                String toolCode = trimToNull(node.path(McpConstants.CONTEXT_OBSERVATION_TOOL_CODE).asText(null));
                if (toolCode != null) {
                    return toolCode;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private List<Map<String, Object>> extractQueryKnowledgeFromContext(String contextJson) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            JsonNode observations = mcpObservations(contextJson);
            if (observations == null || !observations.isArray()) {
                return out;
            }

            for (JsonNode observationNode : observations) {
                String toolCode = trimToNull(observationNode.path(McpConstants.CONTEXT_OBSERVATION_TOOL_CODE).asText(null));
                if (!isSemanticCatalogToolCode(toolCode)) {
                    continue;
                }
                String rawObservationJson = observationNode.path(McpConstants.CONTEXT_OBSERVATION_JSON).asText("");
                if (rawObservationJson.isBlank()) {
                    continue;
                }
                JsonNode observationJson = objectMapper.readTree(rawObservationJson);
                JsonNode queryKnowledge = observationJson.path(SemanticCatalogConstants.KEY_QUERY_KNOWLEDGE);
                if (!queryKnowledge.isArray()) {
                    continue;
                }
                for (JsonNode queryRow : queryKnowledge) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(SemanticCatalogConstants.KEY_QUERY_TEXT, queryRow.path(SemanticCatalogConstants.KEY_QUERY_TEXT).asText(""));
                    row.put(SemanticCatalogConstants.KEY_DESCRIPTION, queryRow.path(SemanticCatalogConstants.KEY_DESCRIPTION).asText(""));
                    row.put(SemanticCatalogConstants.KEY_PREPARED_SQL, queryRow.path(SemanticCatalogConstants.KEY_PREPARED_SQL).asText(""));
                    row.put(SemanticCatalogConstants.KEY_TAGS, toStringList(queryRow.path(SemanticCatalogConstants.KEY_TAGS)));
                    row.put(SemanticCatalogConstants.KEY_API_HINTS, toStringList(queryRow.path(SemanticCatalogConstants.KEY_API_HINTS)));
                    out.add(row);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private JsonNode mcpObservations(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(contextJson);
            return root.path(McpConstants.CONTEXT_KEY_MCP).path(McpConstants.CONTEXT_KEY_OBSERVATIONS);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isSemanticCatalogToolCode(String toolCode) {
        if (toolCode == null || toolCode.isBlank()) {
            return false;
        }
        String normalized = toolCode.trim().toLowerCase(Locale.ROOT);
        return SemanticCatalogConstants.SOURCE_DB_SEMANTIC_CATALOG.equalsIgnoreCase(normalized)
                || LEGACY_KNOWLEDGE_TOOL_CODE.equalsIgnoreCase(normalized);
    }

    private List<String> toStringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = trimToNull(item.asText(null));
                if (value != null) {
                    values.add(value);
                }
            }
            return values;
        }
        String raw = trimToNull(node.asText(null));
        if (raw == null) {
            return List.of();
        }
        String[] parts = raw.split("[,|]");
        for (String part : parts) {
            String value = trimToNull(part);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private String toCsv(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(v -> trimToNull(asText(v)))
                    .filter(Objects::nonNull)
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
        }
        String text = trimToNull(asText(value));
        return text == null ? null : text;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
