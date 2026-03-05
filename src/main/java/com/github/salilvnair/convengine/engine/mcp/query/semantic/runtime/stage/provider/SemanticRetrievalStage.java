package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.RetrievalResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.SemanticEntityTableRetriever;
import com.github.salilvnair.convengine.engine.core.step.annotation.StartStep;
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
        String determinant = ConvEngineAuditStage.SEMANTIC_RETRIEVAL_STAGE.name();
        payload.put("determinant", determinant);
        payload.put("confidence", retrieval == null ? null : retrieval.confidence());
        payload.put("candidateEntitiesCount",
                retrieval == null || retrieval.candidateEntities() == null ? 0 : retrieval.candidateEntities().size());
        payload.put("candidateTablesCount",
                retrieval == null || retrieval.candidateTables() == null ? 0 : retrieval.candidateTables().size());
        payload.put("question", retrieval == null ? null : retrieval.question());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("stage", stageCode());
        meta.put("toolCode", resolveToolCode());
        meta.put("retrievalDone", true);
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
}
