package com.github.salilvnair.convengine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.salilvnair.convengine.audit.dispatch.AuditEventDispatcher;
import com.github.salilvnair.convengine.audit.dispatch.AuditStageControl;
import com.github.salilvnair.convengine.audit.persistence.AuditPersistenceStrategyFactory;
import com.github.salilvnair.convengine.config.ConvEngineAuditConfig;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.entity.CeConversation;
import com.github.salilvnair.convengine.service.ConversationCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
public class DbAuditService implements AuditService {

    private static final Set<String> HISTORY_AI_STAGES = Set.of(
            ConvEngineAuditStage.INTENT_AGENT_LLM_OUTPUT.value(),
            ConvEngineAuditStage.MCP_PLAN_LLM_OUTPUT.value(),
            ConvEngineAuditStage.RESOLVE_RESPONSE_LLM_OUTPUT.value(),
            ConvEngineAuditStage.ASSISTANT_OUTPUT.value(),
            ConvEngineAuditStage.RESPONSE_EXACT.value());

    private final ConversationCacheService cacheService;
    private final AuditStageControl stageControl;
    private final AuditEventDispatcher eventDispatcher;
    private final ConvEngineAuditConfig auditConfig;
    private final AuditPersistenceStrategyFactory persistenceStrategyFactory;
    private final ObjectMapper mapper;

    public DbAuditService(
            ConversationCacheService cacheService,
            AuditStageControl stageControl,
            AuditEventDispatcher eventDispatcher,
            ConvEngineAuditConfig auditConfig,
            AuditPersistenceStrategyFactory persistenceStrategyFactory) {
        this.cacheService = cacheService;
        this.stageControl = stageControl;
        this.eventDispatcher = eventDispatcher;
        this.auditConfig = auditConfig;
        this.persistenceStrategyFactory = persistenceStrategyFactory;

        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void audit(String stage, UUID conversationId, String payloadJson) {
        try {
            if (!stageControl.shouldAudit(stage, conversationId)) {
                return;
            }
            String normalizedPayload = normalizePayload(stage, conversationId, payloadJson);
            CeAudit record = CeAudit.builder()
                    .conversationId(conversationId)
                    .stage(stage)
                    .payloadJson(normalizedPayload)
                    .createdAt(OffsetDateTime.now())
                    .build();

            List<CeAudit> persisted = persistenceStrategyFactory.currentStrategy().persist(record);
            for (CeAudit auditEvent : persisted) {
                eventDispatcher.dispatch(auditEvent);
            }
        } catch (Exception e) {
            log.error("Failed to save audit record for conversationId: {} at stage: {}, payload: {}", conversationId,
                    stage, payloadJson, e);
            // Audit failures must never break the request pipeline.
            // We log and continue so APIs remain non-500 even when audit storage is
            // unavailable.
        }
    }

    @Override
    public void flushPending(UUID conversationId) {
        try {
            List<CeAudit> flushed = persistenceStrategyFactory.currentStrategy().flushPending(conversationId);
            for (CeAudit auditEvent : flushed) {
                eventDispatcher.dispatch(auditEvent);
            }
        } catch (Exception e) {
            log.warn("Deferred audit flush failed convId={} msg={}", conversationId, e.getMessage());
        }
    }

    private String normalizePayload(String stage, UUID conversationId, String payloadJson) {
        try {
            ObjectNode root = mapper.createObjectNode();
            if (payloadJson != null && !payloadJson.isBlank()) {
                var payloadNode = mapper.readTree(payloadJson);
                if (payloadNode.isObject()) {
                    root.setAll((ObjectNode) payloadNode);
                } else {
                    root.set("value", payloadNode);
                }
            }
            ObjectNode meta = root.withObject("_meta");
            meta.put("stage", stage);
            meta.put("conversationId", String.valueOf(conversationId));
            meta.put("emittedAt", OffsetDateTime.now().toString());
            CeConversation conversation = cacheService.getConversation(conversationId).orElse(null);
            String intent = resolveIntent(root, conversation);
            String state = resolveState(root, conversation);
            if (intent != null && !intent.isBlank()) {
                meta.put("intent", intent);
            }
            if (state != null && !state.isBlank()) {
                meta.put("state", state);
            }
            addInputParamsMeta(root, meta, conversation);
            addUserInputParamsMeta(root, meta);
            addContextDictMeta(meta, conversation);
            addSessionMeta(meta, conversationId, conversation);
            if (auditConfig.isCacheInspector()) {
                addCacheMeta(root);
            }
            if (!auditConfig.isPersistMeta()) {
                root.remove("_meta");
            }
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            try {
                ObjectNode fallback = mapper.createObjectNode();
                fallback.put("raw_payload", payloadJson == null ? "" : payloadJson);
                fallback.put("note", "payload_was_not_valid_json");
                ObjectNode meta = fallback.withObject("_meta");
                meta.put("stage", stage);
                meta.put("conversationId", String.valueOf(conversationId));
                meta.put("emittedAt", OffsetDateTime.now().toString());
                CeConversation conversation = cacheService.getConversation(conversationId).orElse(null);
                if (conversation != null) {
                    if (conversation.getIntentCode() != null && !conversation.getIntentCode().isBlank()) {
                        meta.put("intent", conversation.getIntentCode());
                    }
                    if (conversation.getStateCode() != null && !conversation.getStateCode().isBlank()) {
                        meta.put("state", conversation.getStateCode());
                    }
                }
                addInputParamsMeta(fallback, meta, conversation);
                addUserInputParamsMeta(fallback, meta);
                addContextDictMeta(meta, conversation);
                addSessionMeta(meta, conversationId, conversation);
                if (auditConfig.isCacheInspector()) {
                    addCacheMeta(fallback);
                }
                if (!auditConfig.isPersistMeta()) {
                    fallback.remove("_meta");
                }
                return mapper.writeValueAsString(fallback);
            } catch (Exception ignored) {
                return "{\"raw_payload\":\"<unserializable>\",\"note\":\"payload_was_not_valid_json\"}";
            }
        }
    }

    private String resolveIntent(ObjectNode root, CeConversation conversation) {
        EngineSession session = AuditSessionContext.get();
        if (session != null && session.getIntent() != null && !session.getIntent().isBlank()) {
            return session.getIntent();
        }
        String fromPayload = readText(root, "intent");
        if (fromPayload != null && !fromPayload.isBlank()) {
            return fromPayload;
        }
        return conversation == null ? null : conversation.getIntentCode();
    }

    private String resolveState(ObjectNode root, CeConversation conversation) {
        EngineSession session = AuditSessionContext.get();
        if (session != null && session.getState() != null && !session.getState().isBlank()) {
            return session.getState();
        }
        String fromPayload = readText(root, "state");
        if (fromPayload != null && !fromPayload.isBlank()) {
            return fromPayload;
        }
        return conversation == null ? null : conversation.getStateCode();
    }

    private String readText(ObjectNode root, String field) {
        try {
            if (root == null || field == null) {
                return null;
            }
            var node = root.path(field);
            if (!node.isTextual()) {
                node = root.path("_meta").path(field);
            }
            return node.isTextual() ? node.asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void addInputParamsMeta(ObjectNode root, ObjectNode meta, CeConversation conversation) {
        try {
            ObjectNode resolved = resolveInputParamsNode(root, conversation);
            if (resolved != null) {
                meta.set("inputParams", resolved);
            }
        } catch (Exception ignored) {
            // best-effort metadata enrichment only
        }
    }

    private ObjectNode resolveInputParamsNode(ObjectNode root, CeConversation conversation) {
        EngineSession session = AuditSessionContext.get();
        if (session != null && session.getInputParams() != null && !session.getInputParams().isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>(session.getInputParams());
            var node = mapper.valueToTree(copy);
            if (node.isObject()) {
                return (ObjectNode) node;
            }
        }
        if (root != null) {
            var payloadNode = root.path("inputParams");
            if (payloadNode.isObject() && !payloadNode.isEmpty()) {
                return payloadNode.deepCopy();
            }
        }
        if (conversation != null && conversation.getInputParamsJson() != null
                && !conversation.getInputParamsJson().isBlank()) {
            try {
                var conversationNode = mapper.readTree(conversation.getInputParamsJson());
                if (conversationNode.isObject() && !conversationNode.isEmpty()) {
                    return (ObjectNode) conversationNode;
                }
            } catch (Exception ignored) {
                // invalid persisted json should not break audit flow
            }
        }
        return null;
    }

    private void addUserInputParamsMeta(ObjectNode root, ObjectNode meta) {
        try {
            ObjectNode resolved = resolveUserInputParamsNode(root);
            if (resolved != null) {
                meta.set("userInputParams", resolved);
            }
        } catch (Exception ignored) {
            // best-effort metadata enrichment only
        }
    }

    private ObjectNode resolveUserInputParamsNode(ObjectNode root) {
        EngineSession session = AuditSessionContext.get();
        if (session != null
                && session.getEngineContext() != null
                && session.getEngineContext().getUserInputParams() != null
                && !session.getEngineContext().getUserInputParams().isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>(session.getEngineContext().getUserInputParams());
            var node = mapper.valueToTree(copy);
            if (node.isObject()) {
                return (ObjectNode) node;
            }
        }
        if (root != null) {
            var payloadNode = root.path("userInputParams");
            if (payloadNode.isObject() && !payloadNode.isEmpty()) {
                return payloadNode.deepCopy();
            }
        }
        return null;
    }

    private void addContextDictMeta(ObjectNode meta, CeConversation conversation) {
        try {
            ObjectNode contextNode = resolveContextDictNode(conversation);
            if (contextNode != null) {
                meta.set("contextDict", contextNode);
            }
        } catch (Exception ignored) {
            // best-effort metadata enrichment only
        }
    }

    private ObjectNode resolveContextDictNode(CeConversation conversation) {
        EngineSession session = AuditSessionContext.get();
        if (session != null) {
            Map<String, Object> context = session.contextDict();
            if (context != null) {
                var node = mapper.valueToTree(context);
                if (node.isObject()) {
                    return (ObjectNode) node;
                }
            }
        }
        if (conversation != null && conversation.getContextJson() != null && !conversation.getContextJson().isBlank()) {
            try {
                var contextNode = mapper.readTree(conversation.getContextJson());
                if (contextNode.isObject()) {
                    return (ObjectNode) contextNode;
                }
            } catch (Exception ignored) {
                // invalid persisted json should not break audit flow
            }
        }
        return null;
    }

    private void addSessionMeta(ObjectNode meta, UUID conversationId, CeConversation conversation) {
        try {
            ObjectNode sessionNode = resolveSessionNode(conversationId, conversation);
            meta.set("session", sessionNode);
        } catch (Exception ignored) {
            // best-effort metadata enrichment only
        }
    }

    private ObjectNode resolveSessionNode(UUID conversationId, CeConversation conversation) {
        EngineSession session = AuditSessionContext.get();
        if (session != null) {
            Map<String, Object> sessionMap = session.sessionDict();
            if (sessionMap != null) {
                var node = mapper.valueToTree(sessionMap);
                if (node.isObject()) {
                    return (ObjectNode) node;
                }
            }
        }
        ObjectNode fallback = mapper.createObjectNode();
        fallback.put("conversationId", String.valueOf(conversationId));
        if (conversation != null) {
            if (conversation.getIntentCode() != null && !conversation.getIntentCode().isBlank()) {
                fallback.put("intent", conversation.getIntentCode());
            }
            if (conversation.getStateCode() != null && !conversation.getStateCode().isBlank()) {
                fallback.put("state", conversation.getStateCode());
            }
        }
        return fallback;
    }

    private void addCacheMeta(ObjectNode root) {
        try {
            EngineSession session = AuditSessionContext.get();
            if (session != null && session.getConversation() != null) {
                root.set("_cache", mapper.valueToTree(session.getConversation()));
            }
        } catch (Exception ignored) {
            // best-effort cache metadata only
        }
    }

}
