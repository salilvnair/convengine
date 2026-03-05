package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticExecutionResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticSqlExecutor;
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
import java.time.temporal.TemporalAccessor;

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
        publishExecuted(context, result);
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

    private void publishExecuted(SemanticQueryContext context, SemanticExecutionResult result) {
        EngineSession session = context == null ? null : context.session();
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", "semantic-query");
        String determinant = ConvEngineAuditStage.SQL_EXECUTED.name();
        payload.put("determinant", determinant);
        payload.put("rowCount", result == null ? 0 : result.rowCount());
        payload.put("sampleRow", result == null || result.rows() == null || result.rows().isEmpty() ? null : result.rows().getFirst());
        payload.put("_db", dbPayload(context, result));
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

    private Map<String, Object> dbPayload(SemanticQueryContext context, SemanticExecutionResult result) {
        EngineSession session = context == null ? null : context.session();
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("toolCode", resolveToolCode());
        db.put("sql", context != null && context.compiledSql() != null ? context.compiledSql().sql() : null);
        db.put("params", context != null && context.compiledSql() != null && context.compiledSql().params() != null
                ? sanitizeMap(context.compiledSql().params()) : Map.of());
        db.put("paramCount", context != null && context.compiledSql() != null && context.compiledSql().params() != null
                ? context.compiledSql().params().size() : 0);
        db.put("rowCount", result == null ? 0 : result.rowCount());
        db.put("sampleRow", result == null || result.rows() == null || result.rows().isEmpty() ? null : result.rows().getFirst());
        db.put("conversationId", session == null ? null : session.getConversationId());
        return db;
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (source == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : source.entrySet()) {
            out.put(e.getKey(), sanitizeValue(e.getValue()));
        }
        return out;
    }

    private Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        return value;
    }

    private String resolveToolCode() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        if (cfg == null || cfg.getToolCode() == null || cfg.getToolCode().isBlank()) {
            return "db.semantic.query";
        }
        return cfg.getToolCode().trim();
    }
}
