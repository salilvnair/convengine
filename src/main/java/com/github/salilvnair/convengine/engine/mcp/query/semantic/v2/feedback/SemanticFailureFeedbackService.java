package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.feedback;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.entity.CeSemanticQueryFailure;
import com.github.salilvnair.convengine.repo.SemanticQueryFailureRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticFailureFeedbackService {

    private static final String AUDIT_STAGE = "SEMANTIC_FAILURE_RECORDED";

    private final SemanticQueryFailureRepository failureRepository;
    private final AuditService auditService;

    public void recordFailure(SemanticFailureRecord record) {
        if (record == null) {
            return;
        }
        String question = normalize(record.question());
        if (question == null) {
            // Keep persisted rows meaningful and queryable.
            return;
        }
        try {
            CeSemanticQueryFailure entity = CeSemanticQueryFailure.builder()
                    .conversationId(record.conversationId())
                    .question(question)
                    .generatedSql(normalize(record.generatedSql()))
                    .correctSql(normalize(record.correctSql()))
                    .rootCause(normalize(record.rootCause()))
                    .reason(normalize(record.reason()))
                    .stage(normalize(record.stage()))
                    .metadataJson(JsonUtil.toJson(safeMap(record.metadata())))
                    .build();

            CeSemanticQueryFailure saved = failureRepository.save(entity);
            auditService.audit(AUDIT_STAGE, saved.getConversationId(), auditPayload(saved));
        } catch (Exception ex) {
            // Failure capture must never break user request flow.
            log.warn("Failed to persist semantic query failure feedback: {}", ex.getMessage());
        }
    }

    private Map<String, Object> auditPayload(CeSemanticQueryFailure saved) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("failure_id", saved.getId());
        payload.put("conversation_id", stringify(saved.getConversationId()));
        payload.put("stage", saved.getStage());
        payload.put("root_cause", saved.getRootCause());
        payload.put("has_generated_sql", saved.getGeneratedSql() != null && !saved.getGeneratedSql().isBlank());
        payload.put("has_correct_sql", saved.getCorrectSql() != null && !saved.getCorrectSql().isBlank());
        return payload;
    }

    private Map<String, Object> safeMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(map);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String stringify(UUID conversationId) {
        return conversationId == null ? null : conversationId.toString();
    }
}
