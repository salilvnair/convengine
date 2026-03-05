package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticExecutionResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticSqlExecutor;
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
@MustRunAfter(SemanticSqlCompileStage.class)
@RequiredArgsConstructor
public class SemanticSqlExecuteStage implements SemanticQueryStage {

    private final ObjectProvider<List<SemanticSqlExecutor>> executorsProvider;
    private final ConvEngineMcpConfig mcpConfig;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;

    @Override
    public String stageCode() {
        return "sql-execute";
    }

    @Override
    public void execute(SemanticQueryContext context) {
        if (context.compiledSql() == null) {
            throw new IllegalStateException("sql-compile must complete before sql-execute stage");
        }
        SemanticSqlExecutor executor = resolveExecutor(context);
        SemanticExecutionResult result = executor.execute(context.compiledSql(), context);
        context.executionResult(result);
        publishExecuted(context.session(), result);
    }

    private SemanticSqlExecutor resolveExecutor(SemanticQueryContext context) {
        List<SemanticSqlExecutor> executors = executorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(executors);
        for (SemanticSqlExecutor executor : executors) {
            if (executor != null && executor.supports(context)) {
                return executor;
            }
        }
        throw new IllegalStateException("No SemanticSqlExecutor available.");
    }

    private void publishExecuted(EngineSession session, SemanticExecutionResult result) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", "semantic-query");
        String determinant = ConvEngineAuditStage.SEMANTIC_SQL_EXECUTE_STAGE.name();
        payload.put("determinant", determinant);
        payload.put("rowCount", result == null ? 0 : result.rowCount());
        payload.put("sampleRow", result == null || result.rows() == null || result.rows().isEmpty() ? null : result.rows().getFirst());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("stage", stageCode());
        meta.put("toolCode", resolveToolCode());
        meta.put("sqlExecuted", result != null);
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
