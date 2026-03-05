package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.CandidateEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.CandidateTable;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.SemanticEntityTableRetriever;
import com.github.salilvnair.convengine.engine.core.step.annotation.StartStep;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core.SemanticQueryStage;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;

@Component
@StartStep
@RequiredArgsConstructor
public class SemanticRetrievalStage implements SemanticQueryStage {

    private final ObjectProvider<List<SemanticEntityTableRetriever>> retrieversProvider;
    private final ConvEngineMcpConfig mcpConfig;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;

    @Override
    public String stageCode() {
        return "retrieval";
    }

    @Override
    public void execute(SemanticQueryContext context) {
        SemanticEntityTableRetriever retriever = resolveRetriever(context);
        RetrievalResult retrieval = retriever.retrieve(context.question(), context.session());
        context.retrieval(retrieval);
        publishRetrieved(context.session(), retrieval);
    }

    private SemanticEntityTableRetriever resolveRetriever(SemanticQueryContext context) {
        List<SemanticEntityTableRetriever> retrievers = retrieversProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(retrievers);
        for (SemanticEntityTableRetriever retriever : retrievers) {
            if (retriever != null && retriever.supports(context.session())) {
                return retriever;
            }
        }
        throw new IllegalStateException("No SemanticEntityTableRetriever available.");
    }

    private void publishRetrieved(EngineSession session, RetrievalResult retrieval) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", "semantic-query");
        String determinant = ConvEngineAuditStage.RETRIEVAL_DONE.name();
        payload.put("determinant", determinant);
        payload.put("confidence", retrieval == null ? null : retrieval.confidence());
        payload.put("candidateEntitiesCount",
                retrieval == null || retrieval.candidateEntities() == null ? 0 : retrieval.candidateEntities().size());
        payload.put("candidateTablesCount",
                retrieval == null || retrieval.candidateTables() == null ? 0 : retrieval.candidateTables().size());
        payload.put("question", retrieval == null ? null : retrieval.question());
        payload.put("vectorEmbeddingEnabled", isVectorEnabled());
        payload.put("pureLexical", isPureLexical(retrieval));
        payload.put("embeddingDataReturned", hasVectorEvidence(retrieval));
        payload.put("candidateEntityScores", buildEntityScores(retrieval));
        payload.put("candidateTableScores", buildTableScores(retrieval));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("stage", stageCode());
        meta.put("toolCode", resolveToolCode());
        meta.put("retrievalDone", true);
        meta.put("vectorEmbeddingEnabled", isVectorEnabled());
        meta.put("pureLexical", isPureLexical(retrieval));
        meta.put("embeddingDataReturned", hasVectorEvidence(retrieval));
        payload.put("_meta", meta);

        auditService.audit(determinant, conversationId, payload);
        if (verbosePublisher != null) {
            verbosePublisher.publish(session, getClass().getSimpleName(), determinant, null, resolveToolCode(), false, payload);
        }
    }

    private String resolveToolCode() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        if (cfg == null || cfg.getToolCode() == null || cfg.getToolCode().isBlank()) {
            return "db.semantic.query";
        }
        return cfg.getToolCode().trim();
    }

    private boolean isVectorEnabled() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        return cfg != null && cfg.getVector() != null && cfg.getVector().isEnabled();
    }

    private boolean hasVectorEvidence(RetrievalResult retrieval) {
        if (retrieval == null) {
            return false;
        }
        boolean entityVector = retrieval.candidateEntities() != null && retrieval.candidateEntities().stream()
                .filter(Objects::nonNull)
                .anyMatch(c -> c.vectorScore() > 0.0d || (c.reasons() != null && c.reasons().stream().anyMatch(r -> "vector".equalsIgnoreCase(r))));
        boolean tableVector = retrieval.candidateTables() != null && retrieval.candidateTables().stream()
                .filter(Objects::nonNull)
                .anyMatch(c -> c.vectorScore() > 0.0d || (c.reasons() != null && c.reasons().stream().anyMatch(r -> "vector".equalsIgnoreCase(r))));
        return entityVector || tableVector;
    }

    private boolean isPureLexical(RetrievalResult retrieval) {
        if (retrieval == null) {
            return true;
        }
        boolean hasAnyCandidates = (retrieval.candidateEntities() != null && !retrieval.candidateEntities().isEmpty())
                || (retrieval.candidateTables() != null && !retrieval.candidateTables().isEmpty());
        return hasAnyCandidates && !hasVectorEvidence(retrieval);
    }

    private List<Map<String, Object>> buildEntityScores(RetrievalResult retrieval) {
        if (retrieval == null || retrieval.candidateEntities() == null) {
            return List.of();
        }
        return retrieval.candidateEntities().stream()
                .filter(Objects::nonNull)
                .map(this::toEntityScore)
                .toList();
    }

    private List<Map<String, Object>> buildTableScores(RetrievalResult retrieval) {
        if (retrieval == null || retrieval.candidateTables() == null) {
            return List.of();
        }
        return retrieval.candidateTables().stream()
                .filter(Objects::nonNull)
                .map(this::toTableScore)
                .toList();
    }

    private Map<String, Object> toEntityScore(CandidateEntity candidate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", candidate.name());
        row.put("finalScore", candidate.score());
        row.put("deterministicScore", candidate.deterministicScore());
        row.put("vectorScore", candidate.vectorScore());
        row.put("reasons", candidate.reasons() == null ? List.of() : candidate.reasons());
        row.put("vectorMatched", candidate.vectorScore() > 0.0d || hasReason(candidate.reasons(), "vector"));
        row.put("lexicalMatched", hasReason(candidate.reasons(), "lexical"));
        return row;
    }

    private Map<String, Object> toTableScore(CandidateTable candidate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", candidate.name());
        row.put("entity", candidate.entity());
        row.put("finalScore", candidate.score());
        row.put("deterministicScore", candidate.deterministicScore());
        row.put("vectorScore", candidate.vectorScore());
        row.put("reasons", candidate.reasons() == null ? List.of() : candidate.reasons());
        row.put("vectorMatched", candidate.vectorScore() > 0.0d || hasReason(candidate.reasons(), "vector"));
        row.put("lexicalMatched", hasReason(candidate.reasons(), "lexical"));
        return row;
    }

    private boolean hasReason(List<String> reasons, String reason) {
        return reasons != null && reasons.stream().anyMatch(r -> reason.equalsIgnoreCase(r));
    }
}
