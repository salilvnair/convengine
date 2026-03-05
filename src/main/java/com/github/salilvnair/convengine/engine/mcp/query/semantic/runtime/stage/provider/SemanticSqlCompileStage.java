package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core.SemanticQueryStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.CompiledSql;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.SemanticSqlCompiler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.SemanticSqlPolicyValidator;
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
@MustRunAfter(SemanticAstValidationStage.class)
@RequiredArgsConstructor
public class SemanticSqlCompileStage implements SemanticQueryStage {

    private final ObjectProvider<List<SemanticSqlCompiler>> compilersProvider;
    private final ObjectProvider<List<SemanticSqlPolicyValidator>> validatorsProvider;
    private final ConvEngineMcpConfig mcpConfig;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;

    @Override
    public String stageCode() {
        return "sql-compile";
    }

    @Override
    public void execute(SemanticQueryContext context) {
        if (context.astValidation() == null || !context.astValidation().valid()) {
            throw new IllegalStateException("ast-validation must pass before sql-compile stage");
        }
        SemanticSqlCompiler compiler = resolveCompiler(context);
        CompiledSql compiled = compiler.compile(context);
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
        String determinant = ConvEngineAuditStage.SEMANTIC_SQL_COMPILE_STAGE.name();
        payload.put("determinant", determinant);
        payload.put("sqlPreview", compiled == null ? null : abbreviate(compiled.sql(), 300));
        payload.put("paramCount", compiled == null || compiled.params() == null ? 0 : compiled.params().size());
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
