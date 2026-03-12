package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.constants.ClarificationConstants;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.CandidateEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core.SemanticQueryStage;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@MustRunAfter(SemanticJoinPathStage.class)
@MustRunBefore(SemanticAstGenerationStage.class)
@RequiredArgsConstructor
public class SemanticClarificationStage implements SemanticQueryStage {

    private static final String AUDIT_STAGE_CLARIFICATION_REQUIRED = "SEMANTIC_CLARIFICATION_REQUIRED";

    private final ConvEngineMcpConfig mcpConfig;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;

    @Override
    public String stageCode() {
        return "clarification-check";
    }

    @Override
    public void execute(SemanticQueryContext context) {
        if (context == null || context.retrieval() == null || context.joinPath() == null) {
            throw new IllegalStateException("retrieval and join-path must complete before clarification-check stage");
        }
        ConvEngineMcpConfig.Db.Semantic.Clarification cfg = clarificationCfg();
        if (!cfg.isEnabled()) {
            return;
        }

        RetrievalResult retrieval = context.retrieval();
        double retrievalConfidence = retrievalConfidenceScore(retrieval);
        double joinConfidence = clamp01(context.joinPath().confidence());
        double overallConfidence = weightedConfidence(retrievalConfidence, joinConfidence, cfg);
        boolean ambiguousEntity = hasAmbiguousTopEntities(retrieval, cfg);
        boolean unresolvedJoins = context.joinPath().unresolvedTables() != null && !context.joinPath().unresolvedTables().isEmpty();
        boolean needsClarification = ambiguousEntity || unresolvedJoins || overallConfidence < clamp01(cfg.getConfidenceThreshold());

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("retrievalConfidence", retrievalConfidence);
        diagnostics.put("retrievalConfidenceLabel", retrieval == null ? null : retrieval.confidence());
        diagnostics.put("joinPathConfidence", joinConfidence);
        diagnostics.put("overallConfidence", overallConfidence);
        diagnostics.put("confidenceThreshold", clamp01(cfg.getConfidenceThreshold()));
        diagnostics.put("ambiguousTopEntities", ambiguousEntity);
        diagnostics.put("unresolvedJoinTables", unresolvedJoins);
        diagnostics.put("topEntities", topEntityNames(retrieval, Math.max(2, cfg.getMaxEntityOptions())));

        if (!needsClarification) {
            return;
        }

        String question = buildClarificationQuestion(retrieval, unresolvedJoins, cfg);
        context.clarificationRequired(true);
        context.clarificationQuestion(question);
        context.clarificationReason(ClarificationConstants.REASON_SEMANTIC_QUERY_AMBIGUITY);
        context.clarificationConfidence(overallConfidence);
        context.clarificationDiagnostics(diagnostics);
        context.summary(question);

        EngineSession session = context.session();
        if (session != null) {
            session.setPendingClarificationQuestion(question);
            session.setPendingClarificationReason(ClarificationConstants.REASON_SEMANTIC_QUERY_AMBIGUITY);
            session.addClarificationHistory();
            session.setAwaitingClarification(true);
            session.setLastClarificationQuestion(question);
        }

        publishClarificationRequired(context, question, diagnostics);
    }

    private void publishClarificationRequired(SemanticQueryContext context, String question, Map<String, Object> diagnostics) {
        EngineSession session = context == null ? null : context.session();
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", "semantic-query");
        payload.put("determinant", AUDIT_STAGE_CLARIFICATION_REQUIRED);
        payload.put("reason", ClarificationConstants.REASON_SEMANTIC_QUERY_AMBIGUITY);
        payload.put("question", question);
        payload.put("semanticQuestion", context == null ? null : context.question());
        payload.put("diagnostics", diagnostics == null ? Map.of() : diagnostics);
        payload.put("_meta", Map.of(
                "component", "semantic-query",
                "stage", stageCode(),
                "toolCode", resolveToolCode(),
                "clarificationRequired", true
        ));

        auditService.audit(AUDIT_STAGE_CLARIFICATION_REQUIRED, conversationId, payload);
        if (verbosePublisher != null) {
            verbosePublisher.publish(session, getClass().getSimpleName(), AUDIT_STAGE_CLARIFICATION_REQUIRED, null,
                    resolveToolCode(), false, payload);
        }
    }

    private String buildClarificationQuestion(RetrievalResult retrieval,
                                              boolean unresolvedJoins,
                                              ConvEngineMcpConfig.Db.Semantic.Clarification cfg) {
        List<String> topEntities = topEntityNames(retrieval, Math.max(2, cfg.getMaxEntityOptions()));
        if (topEntities.size() >= 2) {
            return "I found multiple possible entities: " + topEntities.get(0) + " or " + topEntities.get(1)
                    + ". Which one did you mean?";
        }
        if (!topEntities.isEmpty()) {
            return "I interpreted this as " + topEntities.get(0)
                    + ", but confidence is low. Can you confirm the entity and key filters?";
        }
        if (unresolvedJoins) {
            return "I found multiple possible join paths. Can you clarify which business object you want to query?";
        }
        return "I need one clarification before running SQL. Can you specify the exact entity and filter you want?";
    }

    private boolean hasAmbiguousTopEntities(RetrievalResult retrieval, ConvEngineMcpConfig.Db.Semantic.Clarification cfg) {
        if (retrieval == null || retrieval.candidateEntities() == null || retrieval.candidateEntities().size() < 2) {
            return false;
        }
        CandidateEntity first = retrieval.candidateEntities().get(0);
        CandidateEntity second = retrieval.candidateEntities().get(1);
        if (first == null || second == null) {
            return false;
        }
        double gap = Math.abs(first.score() - second.score());
        return gap < clamp01(cfg.getMinTopEntityGap());
    }

    private double weightedConfidence(double retrievalConfidence,
                                      double joinConfidence,
                                      ConvEngineMcpConfig.Db.Semantic.Clarification cfg) {
        double retrievalWeight = Math.max(0.0d, cfg.getRetrievalWeight());
        double joinWeight = Math.max(0.0d, cfg.getJoinWeight());
        double totalWeight = retrievalWeight + joinWeight;
        if (totalWeight <= 0.0d) {
            return (retrievalConfidence + joinConfidence) / 2.0d;
        }
        return clamp01((retrievalConfidence * retrievalWeight + joinConfidence * joinWeight) / totalWeight);
    }

    private double retrievalConfidenceScore(RetrievalResult retrieval) {
        if (retrieval == null || retrieval.confidence() == null) {
            return 0.5d;
        }
        String confidence = retrieval.confidence().trim().toUpperCase(Locale.ROOT);
        return switch (confidence) {
            case "HIGH" -> 0.90d;
            case "MEDIUM" -> 0.70d;
            case "LOW" -> 0.45d;
            default -> 0.50d;
        };
    }

    private List<String> topEntityNames(RetrievalResult retrieval, int max) {
        if (retrieval == null || retrieval.candidateEntities() == null || retrieval.candidateEntities().isEmpty() || max <= 0) {
            return List.of();
        }
        return retrieval.candidateEntities().stream()
                .filter(e -> e != null && e.name() != null && !e.name().isBlank())
                .limit(max)
                .map(CandidateEntity::name)
                .toList();
    }

    private double clamp01(double value) {
        if (value < 0.0d) {
            return 0.0d;
        }
        return Math.min(value, 1.0d);
    }

    private ConvEngineMcpConfig.Db.Semantic.Clarification clarificationCfg() {
        ConvEngineMcpConfig.Db.Semantic semantic = mcpConfig.getDb() == null
                ? new ConvEngineMcpConfig.Db.Semantic()
                : mcpConfig.getDb().getSemantic();
        ConvEngineMcpConfig.Db.Semantic.Clarification clarification = semantic.getClarification();
        return clarification == null ? new ConvEngineMcpConfig.Db.Semantic.Clarification() : clarification;
    }

    private String resolveToolCode() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        if (cfg == null || cfg.getToolCode() == null || cfg.getToolCode().isBlank()) {
            return "db.semantic.query";
        }
        return cfg.getToolCode().trim();
    }
}
