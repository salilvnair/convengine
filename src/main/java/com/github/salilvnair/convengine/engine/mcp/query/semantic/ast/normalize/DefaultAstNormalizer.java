package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.normalize;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.*;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultAstNormalizer implements AstNormalizer {

    private final ObjectProvider<List<AstNormalizeInterceptor>> interceptorsProvider;

    @Override
    public SemanticQueryAstV1 normalize(SemanticQueryAstV1 ast, SemanticModel model, String selectedEntity, EngineSession session) {
        List<AstNormalizeInterceptor> interceptors = interceptorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(interceptors);
        for (AstNormalizeInterceptor interceptor : interceptors) {
            if (interceptor != null && interceptor.supports(session)) {
                interceptor.beforeNormalize(ast, model, selectedEntity, session);
            }
        }
        try {
            if (ast == null || model == null || model.entities() == null) {
                return ast;
            }
            String targetEntity = (ast.entity() == null || ast.entity().isBlank()) ? selectedEntity : ast.entity();
            SemanticEntity entity = model.entities().get(targetEntity);
            if (entity == null || entity.fields() == null || entity.fields().isEmpty()) {
                return ast;
            }
            Map<String, String> aliasToField = buildFieldAliasMap(entity.fields());

            List<String> normalizedSelect = ast.select().stream()
                    .map(field -> normalizeField(field, aliasToField, entity.fields()))
                    .toList();
            List<AstFilter> normalizedFilters = ast.filters().stream()
                    .map(filter -> filter == null ? null : new AstFilter(
                            normalizeField(filter.field(), aliasToField, entity.fields()),
                            filter.op(),
                            filter.value()))
                    .toList();
            List<AstSort> normalizedSort = ast.sort().stream()
                    .map(sort -> sort == null ? null : new AstSort(
                            normalizeField(sort.field(), aliasToField, entity.fields()),
                            sort.direction(),
                            sort.nulls()))
                    .toList();
            List<String> normalizedGroupBy = ast.groupBy().stream()
                    .map(field -> normalizeField(field, aliasToField, entity.fields()))
                    .toList();

            SemanticQueryAstV1 normalized = new SemanticQueryAstV1(
                    ast.astVersion(),
                    targetEntity,
                    normalizedSelect,
                    ast.projections(),
                    normalizedFilters,
                    ast.where(),
                    ast.timeRange(),
                    ast.existsBlocks(),
                    ast.subqueryFilters(),
                    normalizedGroupBy,
                    ast.metrics(),
                    ast.windows(),
                    normalizedSort,
                    ast.having(),
                    ast.limit(),
                    ast.offset(),
                    ast.distinct(),
                    ast.joinHints()
            );
            for (AstNormalizeInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    normalized = interceptor.afterNormalize(normalized, session);
                }
            }
            return normalized;
        } catch (Exception ex) {
            for (AstNormalizeInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    interceptor.onError(ast, session, ex);
                }
            }
            throw ex;
        }
    }

    private Map<String, String> buildFieldAliasMap(Map<String, SemanticField> fields) {
        Map<String, String> aliasToField = new LinkedHashMap<>();
        for (Map.Entry<String, SemanticField> e : fields.entrySet()) {
            String fieldName = e.getKey();
            SemanticField field = e.getValue();
            putAlias(aliasToField, normalizeToken(fieldName), fieldName);
            putAlias(aliasToField, normalizeToken(camelToSnake(fieldName)), fieldName);
            if (field != null && field.column() != null) {
                String col = field.column();
                String colName = col.contains(".") ? col.substring(col.lastIndexOf('.') + 1) : col;
                putAlias(aliasToField, normalizeToken(colName), fieldName);
                if (colName.startsWith("zp_")) {
                    putAlias(aliasToField, normalizeToken(colName.substring(3)), fieldName);
                }
            }
        }
        if (fields.containsKey("requestId")) {
            putAlias(aliasToField, "id", "requestId");
        }
        if (fields.containsKey("requestStatus")) {
            putAlias(aliasToField, "status", "requestStatus");
        }
        if (fields.containsKey("requestedAt")) {
            putAlias(aliasToField, "createdat", "requestedAt");
            putAlias(aliasToField, "created", "requestedAt");
        }
        return aliasToField;
    }

    private void putAlias(Map<String, String> aliasToField, String alias, String fieldName) {
        if (alias == null || alias.isBlank()) {
            return;
        }
        aliasToField.putIfAbsent(alias, fieldName);
    }

    private String normalizeField(String field, Map<String, String> aliasToField, Map<String, SemanticField> knownFields) {
        if (field == null || field.isBlank()) {
            return field;
        }
        if (knownFields.containsKey(field)) {
            return field;
        }
        String normalized = normalizeToken(field);
        String mapped = aliasToField.get(normalized);
        return mapped == null ? field : mapped;
    }

    private String normalizeToken(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    private String camelToSnake(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return input
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase();
    }
}
