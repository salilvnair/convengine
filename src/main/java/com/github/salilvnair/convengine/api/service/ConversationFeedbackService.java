package com.github.salilvnair.convengine.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.api.dto.ConversationFeedbackRequest;
import com.github.salilvnair.convengine.api.dto.ConversationFeedbackResponse;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.mcp.McpConstants;
import com.github.salilvnair.convengine.engine.mcp.knowledge.SemanticCatalogConstants;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.embedding.SemanticEmbeddingService;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.entity.CeMcpUserFeedback;
import com.github.salilvnair.convengine.entity.CeMcpUserQueryKnowledge;
import com.github.salilvnair.convengine.entity.CeUserQueryKnowledge;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import com.github.salilvnair.convengine.repo.McpUserFeedbackRepository;
import com.github.salilvnair.convengine.repo.McpUserQueryKnowledgeRepository;
import com.github.salilvnair.convengine.repo.UserQueryKnowledgeRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ConversationFeedbackService {

    private static final String FEEDBACK_THUMBS_UP = "THUMBS_UP";
    private static final String FEEDBACK_THUMBS_DOWN = "THUMBS_DOWN";

    private static final String TOOL_DB_SEMANTIC_QUERY = "db.semantic.query";
    private static final String TOOL_DB_SEMANTIC_CATALOG = "db.semantic.catalog";
    private static final String TOOL_DB_KNOWLEDGE_GRAPH = "db.knowledge.graph";
    private static final String TOOL_DBKG_INVESTIGATE_EXECUTE = "dbkg.investigate.execute";

    private final ConversationRepository conversationRepository;
    private final McpUserFeedbackRepository feedbackRepository;
    private final McpUserQueryKnowledgeRepository legacyUserQueryKnowledgeRepository;
    private final UserQueryKnowledgeRepository userQueryKnowledgeRepository;
    private final SemanticEmbeddingService semanticEmbeddingService;
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
        List<Map<String, Object>> legacyCatalogQueryKnowledge = extractCatalogQueryKnowledgeFromContext(conversation.getContextJson());

        CeMcpUserFeedback feedback = CeMcpUserFeedback.builder()
                .conversationId(conversation.getConversationId())
                .feedbackType(normalizedFeedback)
                .messageId(trimToNull(request.getMessageId()))
                .intentCode(trimToNull(conversation.getIntentCode()))
                .stateCode(trimToNull(conversation.getStateCode()))
                .userQuery(trimToNull(conversation.getLastUserText()))
                .assistantResponse(assistantResponse)
                .mcpToolCode(resolveLastMcpToolCode(conversation.getContextJson()))
                .capturedQueryKnowledgeCount(legacyCatalogQueryKnowledge.size())
                .appliedQueryKnowledgeJson(JsonUtil.toJson(legacyCatalogQueryKnowledge))
                .metadataJson(JsonUtil.toJson(request.getMetadata() == null ? Map.of() : request.getMetadata()))
                .build();
        feedback = feedbackRepository.save(feedback);

        persistLegacyUserQueryKnowledge(legacyCatalogQueryKnowledge);

        List<FeedbackKnowledgeEntry> entries = extractKnowledgeEntriesForAllModes(
                conversation,
                feedback,
                normalizedFeedback
        );
        int savedUserKnowledgeCount = 0;
        int embeddedUserKnowledgeCount = 0;
        boolean shouldEmbed = FEEDBACK_THUMBS_UP.equalsIgnoreCase(normalizedFeedback);
        for (FeedbackKnowledgeEntry entry : entries) {
            if (entry.queryText() == null || entry.queryText().isBlank()) {
                continue;
            }
            CeUserQueryKnowledge knowledge = CeUserQueryKnowledge.builder()
                    .conversationId(conversation.getConversationId())
                    .feedbackId(feedback.getFeedbackId())
                    .feedbackType(normalizedFeedback)
                    .toolCode(entry.toolCode())
                    .intentCode(trimToNull(conversation.getIntentCode()))
                    .stateCode(trimToNull(conversation.getStateCode()))
                    .queryText(trimToNull(entry.queryText()))
                    .description(trimToNull(entry.description()))
                    .preparedSql(trimToNull(entry.preparedSql()))
                    .tags(toCsv(entry.tags()))
                    .apiHints(toCsv(entry.apiHints()))
                    .metadataJson(JsonUtil.toJson(entry.metadata()))
                    .build();
            knowledge = userQueryKnowledgeRepository.save(knowledge);
            savedUserKnowledgeCount++;

            if (shouldEmbed && semanticEmbeddingService.indexUserQueryKnowledge(knowledge)) {
                userQueryKnowledgeRepository.save(knowledge);
                embeddedUserKnowledgeCount++;
            }
        }

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("conversation_id", String.valueOf(conversation.getConversationId()));
        auditPayload.put("feedback_type", normalizedFeedback);
        auditPayload.put("message_id", trimToNull(request.getMessageId()));
        auditPayload.put("captured_query_knowledge_count", legacyCatalogQueryKnowledge.size());
        auditPayload.put("captured_user_query_knowledge_count", savedUserKnowledgeCount);
        auditPayload.put("embedded_user_query_knowledge_count", embeddedUserKnowledgeCount);
        auditPayload.put("feedback_id", feedback.getFeedbackId());
        auditService.audit(ConvEngineAuditStage.MCP_USER_FEEDBACK, conversation.getConversationId(), auditPayload);

        return ConversationFeedbackResponse.builder()
                .success(true)
                .feedbackId(feedback.getFeedbackId())
                .capturedQueryKnowledgeCount(legacyCatalogQueryKnowledge.size())
                .capturedUserQueryKnowledgeCount(savedUserKnowledgeCount)
                .embeddedUserQueryKnowledgeCount(embeddedUserKnowledgeCount)
                .message("Feedback saved.")
                .build();
    }

    private void persistLegacyUserQueryKnowledge(List<Map<String, Object>> queryKnowledgeRows) {
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
            legacyUserQueryKnowledgeRepository.save(knowledge);
        }
    }

    private List<FeedbackKnowledgeEntry> extractKnowledgeEntriesForAllModes(
            CeConversation conversation,
            CeMcpUserFeedback feedback,
            String feedbackType) {
        List<FeedbackKnowledgeEntry> out = new ArrayList<>();
        JsonNode observations = mcpObservations(conversation.getContextJson());
        if (observations == null || !observations.isArray()) {
            return out;
        }

        String userQuery = trimToNull(conversation.getLastUserText());
        String intent = trimToNull(conversation.getIntentCode());
        String state = trimToNull(conversation.getStateCode());

        Set<String> dedupe = new LinkedHashSet<>();
        for (JsonNode observationNode : observations) {
            String toolCode = trimToNull(observationNode.path(McpConstants.CONTEXT_OBSERVATION_TOOL_CODE).asText(null));
            String rawObservationJson = observationNode.path(McpConstants.CONTEXT_OBSERVATION_JSON).asText("");
            JsonNode observationJson = parseJson(rawObservationJson);
            List<FeedbackKnowledgeEntry> extracted = extractFromObservation(
                    toolCode,
                    userQuery,
                    intent,
                    state,
                    feedbackType,
                    feedback.getFeedbackId(),
                    observationJson
            );
            for (FeedbackKnowledgeEntry entry : extracted) {
                String signature = (entry.toolCode() + "|" + entry.queryText() + "|" + entry.preparedSql()).toLowerCase(Locale.ROOT);
                if (dedupe.add(signature)) {
                    out.add(entry);
                }
            }
        }
        return out;
    }

    private List<FeedbackKnowledgeEntry> extractFromObservation(
            String toolCode,
            String userQuery,
            String intent,
            String state,
            String feedbackType,
            Long feedbackId,
            JsonNode observationJson) {
        List<FeedbackKnowledgeEntry> out = new ArrayList<>();
        if (observationJson == null || observationJson.isMissingNode() || observationJson.isNull()) {
            return out;
        }

        if (isSemanticCatalogToolCode(toolCode)) {
            JsonNode queryKnowledge = observationJson.path(SemanticCatalogConstants.KEY_QUERY_KNOWLEDGE);
            if (queryKnowledge.isArray()) {
                for (JsonNode queryRow : queryKnowledge) {
                    String queryText = trimToNull(queryRow.path(SemanticCatalogConstants.KEY_QUERY_TEXT).asText(userQuery));
                    String description = trimToNull(queryRow.path(SemanticCatalogConstants.KEY_DESCRIPTION).asText(null));
                    String preparedSql = trimToNull(queryRow.path(SemanticCatalogConstants.KEY_PREPARED_SQL).asText(null));
                    List<String> tags = toStringList(queryRow.path(SemanticCatalogConstants.KEY_TAGS));
                    List<String> apiHints = toStringList(queryRow.path(SemanticCatalogConstants.KEY_API_HINTS));
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("source", "semantic-catalog");
                    meta.put("feedbackId", feedbackId);
                    meta.put("intent", intent);
                    meta.put("state", state);
                    meta.put("feedbackType", feedbackType);
                    meta.put("toolCode", toolCode);
                    out.add(new FeedbackKnowledgeEntry(toolCode, queryText, description, preparedSql, tags, apiHints, meta));
                }
            }
            return out;
        }

        if (isSemanticQueryToolCode(toolCode) || isDbkgToolCode(toolCode)) {
            String preparedSql = firstNonBlank(
                    pathText(observationJson, "_db", "sql"),
                    trimToNull(observationJson.path("compiledSql").asText(null)),
                    trimToNull(observationJson.path("sql").asText(null)),
                    trimToNull(observationJson.path("preparedSql").asText(null))
            );
            String summary = firstNonBlank(
                    trimToNull(observationJson.path("summary").asText(null)),
                    trimToNull(observationJson.path("message").asText(null))
            );
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", isSemanticQueryToolCode(toolCode) ? "semantic-query" : "dbkg");
            meta.put("feedbackId", feedbackId);
            meta.put("intent", intent);
            meta.put("state", state);
            meta.put("feedbackType", feedbackType);
            meta.put("toolCode", toolCode);
            meta.put("observation", observationJson);

            List<String> tags = new ArrayList<>();
            tags.add(firstNonBlank(intent, "UNKNOWN_INTENT"));
            tags.add(firstNonBlank(state, "UNKNOWN_STATE"));
            tags.add(firstNonBlank(toolCode, "UNKNOWN_TOOL"));
            String queryText = firstNonBlank(userQuery, summary, "user feedback");
            out.add(new FeedbackKnowledgeEntry(
                    toolCode,
                    queryText,
                    summary == null ? "Captured from " + toolCode : summary,
                    preparedSql,
                    tags,
                    List.of("feedback_capture", "mcp_observation"),
                    meta
            ));
        }
        return out;
    }

    private List<Map<String, Object>> extractCatalogQueryKnowledgeFromContext(String contextJson) {
        List<Map<String, Object>> out = new ArrayList<>();
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
            JsonNode observationJson = parseJson(rawObservationJson);
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
        return out;
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
        return null;
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

    private JsonNode parseJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String pathText(JsonNode node, String first, String second) {
        if (node == null) {
            return null;
        }
        JsonNode firstNode = node.path(first);
        if (firstNode.isMissingNode() || firstNode.isNull()) {
            return null;
        }
        JsonNode secondNode = firstNode.path(second);
        if (secondNode.isTextual()) {
            return trimToNull(secondNode.asText(null));
        }
        return null;
    }

    private boolean isSemanticCatalogToolCode(String toolCode) {
        if (toolCode == null || toolCode.isBlank()) {
            return false;
        }
        return TOOL_DB_SEMANTIC_CATALOG.equalsIgnoreCase(toolCode.trim());
    }

    private boolean isSemanticQueryToolCode(String toolCode) {
        if (toolCode == null || toolCode.isBlank()) {
            return false;
        }
        return TOOL_DB_SEMANTIC_QUERY.equalsIgnoreCase(toolCode.trim());
    }

    private boolean isDbkgToolCode(String toolCode) {
        if (toolCode == null || toolCode.isBlank()) {
            return false;
        }
        String normalized = toolCode.trim().toLowerCase(Locale.ROOT);
        return TOOL_DB_KNOWLEDGE_GRAPH.equalsIgnoreCase(normalized)
                || TOOL_DBKG_INVESTIGATE_EXECUTE.equalsIgnoreCase(normalized)
                || normalized.contains("dbkg");
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            String n = trimToNull(v);
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    private record FeedbackKnowledgeEntry(
            String toolCode,
            String queryText,
            String description,
            String preparedSql,
            List<String> tags,
            List<String> apiHints,
            Map<String, Object> metadata
    ) {
    }
}

