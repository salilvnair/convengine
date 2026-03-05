package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core.SemanticQueryStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.interceptor.AstCompilationInterceptor;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompiledSql;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.SemanticSqlCompiler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.policy.SemanticSqlPolicyValidator;
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
@MustRunAfter(SemanticAstValidationStage.class)
@RequiredArgsConstructor
public class SemanticSqlCompileStage implements SemanticQueryStage {

    private final ObjectProvider<List<SemanticSqlCompiler>> compilersProvider;
    private final ObjectProvider<List<SemanticSqlPolicyValidator>> validatorsProvider;
    private final ObjectProvider<List<AstCompilationInterceptor>> compilationInterceptorsProvider;
    private final ConvEngineMcpConfig mcpConfig;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;

    @Override
    public String stageCode() {
        return "sql-compile";
    }

    @Override
    public void execute(SemanticQueryContext context) {
        if (context.astValidation() == null || !context.astValidation().valid() || context.canonicalAst() == null) {
            throw new IllegalStateException("ast-validation must pass before sql-compile stage");
        }
        SemanticSqlCompiler compiler = resolveCompiler(context);
        List<AstCompilationInterceptor> compilationInterceptors = compilationInterceptorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(compilationInterceptors);
        for (AstCompilationInterceptor interceptor : compilationInterceptors) {
            if (interceptor != null && interceptor.supports(context)) {
                interceptor.beforeCompile(context.canonicalAst(), context);
            }
        }
        CompiledSql compiled;
        try {
            compiled = compiler.compile(context);
            for (AstCompilationInterceptor interceptor : compilationInterceptors) {
                if (interceptor != null && interceptor.supports(context)) {
                    compiled = interceptor.afterCompile(compiled, context);
                }
            }
        } catch (Exception ex) {
            for (AstCompilationInterceptor interceptor : compilationInterceptors) {
                if (interceptor != null && interceptor.supports(context)) {
                    interceptor.onError(context.canonicalAst(), context, ex);
                }
            }
            throw ex;
        }
        resolveValidator(context).validate(compiled, context);
        context.compiledSql(compiled);
        publishCompiled(context.session(), compiled);
    }

    private SemanticSqlCompiler resolveCompiler(SemanticQueryContext context) {
        List<SemanticSqlCompiler> compilers = compilersProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(compilers);
        for (SemanticSqlCompiler compiler : compilers) {
            if (compiler != null && compiler.supports(context)) {
                return compiler;
            }
        }
        throw new IllegalStateException("No SemanticSqlCompiler available.");
    }

    private SemanticSqlPolicyValidator resolveValidator(SemanticQueryContext context) {
        List<SemanticSqlPolicyValidator> validators = validatorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(validators);
        for (SemanticSqlPolicyValidator validator : validators) {
            if (validator != null && validator.supports(context)) {
                return validator;
            }
        }
        throw new IllegalStateException("No SemanticSqlPolicyValidator available.");
    }

    private void publishCompiled(EngineSession session, CompiledSql compiled) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", "semantic-query");
        String determinant = ConvEngineAuditStage.SQL_COMPILED.name();
        payload.put("determinant", determinant);
        payload.put("sqlPreview", compiled == null ? null : abbreviate(compiled.sql(), 300));
        payload.put("sql", compiled == null ? null : compiled.sql());
        payload.put("paramCount", compiled == null || compiled.params() == null ? 0 : compiled.params().size());
        payload.put("_db", dbPayload(compiled, null));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("stage", stageCode());
        meta.put("toolCode", resolveToolCode());
        meta.put("sqlCompiled", compiled != null && compiled.sql() != null && !compiled.sql().isBlank());
        payload.put("_meta", meta);

        auditService.audit(determinant, conversationId, payload);
        if (verbosePublisher != null) {
            verbosePublisher.publish(session, getClass().getSimpleName(), determinant, null, resolveToolCode(), false, payload);
        }
    }

    private Map<String, Object> dbPayload(CompiledSql compiled, Integer rowCount) {
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("toolCode", resolveToolCode());
        db.put("sql", compiled == null ? null : compiled.sql());
        db.put("sqlPreview", compiled == null ? null : abbreviate(compiled.sql(), 300));
        db.put("params", compiled == null || compiled.params() == null ? Map.of() : sanitizeMap(compiled.params()));
        db.put("paramCount", compiled == null || compiled.params() == null ? 0 : compiled.params().size());
        db.put("rowCount", rowCount == null ? 0 : rowCount);
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

    private String abbreviate(String text, int max) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }
}
