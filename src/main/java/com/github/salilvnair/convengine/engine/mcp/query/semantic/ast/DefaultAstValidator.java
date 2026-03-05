package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class DefaultAstValidator implements AstValidator {

    private static final Set<String> ALLOWED_OPS = Set.of("=", "!=", ">", ">=", "<", "<=", "IN", "BETWEEN", "LIKE", "IS_NULL", "IS_NOT_NULL");

    private final ConvEngineMcpConfig mcpConfig;
    private final ObjectProvider<List<AstValidationInterceptor>> interceptorsProvider;

    @Override
    public AstValidationResult validate(SemanticQueryAst ast, SemanticModel model, JoinPathPlan joinPathPlan, EngineSession session) {
        List<AstValidationInterceptor> interceptors = interceptorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(interceptors);
        for (AstValidationInterceptor interceptor : interceptors) {
            if (interceptor != null && interceptor.supports(session)) {
                interceptor.beforeValidate(ast, model, joinPathPlan, session);
            }
        }
        try {
            List<String> errors = new ArrayList<>();
            if (ast == null) {
                errors.add("ast is null");
                return new AstValidationResult(false, errors);
            }
            if (ast.entity() == null || ast.entity().isBlank()) {
                errors.add("entity is required");
                return new AstValidationResult(false, errors);
            }
            SemanticEntity entity = model.entities().get(ast.entity());
            if (entity == null) {
                errors.add("entity not found: " + ast.entity());
                return new AstValidationResult(false, errors);
            }

            Set<String> fieldNames = new HashSet<>(entity.fields().keySet());
            for (String selected : ast.select()) {
                if (!fieldNames.contains(selected)) {
                    errors.add("unknown select field: " + selected);
                }
            }

            for (AstFilter filter : ast.filters()) {
                if (filter == null) {
                    continue;
                }
                if (filter.field() == null || !fieldNames.contains(filter.field())) {
                    errors.add("unknown filter field: " + filter.field());
                }
                String op = filter.op() == null ? "" : filter.op().toUpperCase(Locale.ROOT);
                if (!ALLOWED_OPS.contains(op)) {
                    errors.add("unsupported filter op: " + filter.op());
                }
            }

            ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
            int defaultLimit = Math.max(cfg.getDefaultLimit(), 1);
            int maxLimit = Math.max(cfg.getMaxLimit(), defaultLimit);
            if (ast.limit() != null && ast.limit() > maxLimit) {
                errors.add("limit exceeds max: " + ast.limit());
            }
            if (joinPathPlan != null && !joinPathPlan.unresolvedTables().isEmpty()) {
                errors.add("join path unresolved tables: " + joinPathPlan.unresolvedTables());
            }

            AstValidationResult result = new AstValidationResult(errors.isEmpty(), List.copyOf(errors));
            AstValidationResult current = result;
            for (AstValidationInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    current = interceptor.afterValidate(current, session);
                }
            }
            return current;
        } catch (Exception ex) {
            for (AstValidationInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    interceptor.onError(ast, session, ex);
                }
            }
            throw ex;
        }
    }
}
