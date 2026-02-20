package com.github.salilvnair.convengine.audit;

import com.github.salilvnair.convengine.util.JsonUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public interface AuditService {
    void audit(String stage, UUID conversationId, String payloadJson);

    default void audit(ConvEngineAuditStage stage, UUID conversationId, String payloadJson) {
        audit(stage.value(), conversationId, payloadJson);
    }

    default void audit(String stage, UUID conversationId, Map<String, ?> payload) {
        audit(stage, conversationId, JsonUtil.toJson(payload == null ? Map.of() : payload));
    }

    default void audit(ConvEngineAuditStage stage, UUID conversationId, Map<String, ?> payload) {
        audit(stage.value(), conversationId, payload);
    }

    default void audit(String stage, UUID conversationId, Object payload) {
        if (payload == null) {
            audit(stage, conversationId, "{}");
            return;
        }
        if (payload instanceof String s) {
            audit(stage, conversationId, s);
            return;
        }
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((k, v) -> normalized.put(String.valueOf(k), v));
            audit(stage, conversationId, normalized);
            return;
        }
        audit(stage, conversationId, JsonUtil.toJson(payload));
    }

    default void audit(ConvEngineAuditStage stage, UUID conversationId, Object payload) {
        audit(stage.value(), conversationId, payload);
    }

    default void flushPending(UUID conversationId) {
        // no-op by default
    }
}
