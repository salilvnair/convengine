package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstTimeRange;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompiledSql;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.policy.SemanticSqlPolicyValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedJoinPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedSelectItem;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedSemanticPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedTimeRange;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticCompiledSql;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticGuardrailResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticQueryRequestV2;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticQueryResponseV2;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticToolMeta;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.feedback.SemanticFailureFeedbackService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.feedback.SemanticFailureRecord;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SemanticQueryV2Service {

    private static final String TOOL_CODE = "db.semantic.query";
    private static final String VERSION = "v2";

    private final ObjectProvider<List<SemanticSqlPolicyValidator>> validatorsProvider;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;
    private final SemanticFailureFeedbackService failureFeedbackService;

    public SemanticQueryResponseV2 query(SemanticQueryRequestV2 request, EngineSession session) {
        UUID conversationId = safeConversationId(request, session);
        String question = session == null ? "" : safeText(session.getUserText());
        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("tool", TOOL_CODE);
        inputPayload.put("version", VERSION);
        inputPayload.put("semantic_v2_stage", "query");
        inputPayload.put("semantic_v2_event", "input");
        inputPayload.put("strictMode", request != null && Boolean.TRUE.equals(request.strictMode()));
        inputPayload.put("dryRun", request != null && Boolean.TRUE.equals(request.dryRun()));
        inputPayload.put("question", question);
        inputPayload.put("resolvedPlan", request == null ? null : request.resolvedPlan());
        audit("SEMANTIC_QUERY_V2_INPUT", conversationId, inputPayload, session, false);

        try {
            if (request == null || request.resolvedPlan() == null) {
                throw new IllegalArgumentException("resolvedPlan is required for db.semantic.query v2.");
            }

            ResolvedSemanticPlan plan = request.resolvedPlan();
            boolean strictMode = Boolean.TRUE.equals(request.strictMode());

            validatePlan(plan, strictMode);
            SemanticQueryAstV1 ast = buildAst(plan);
            CompiledSql compiled = compile(plan);

            SemanticGuardrailResult guardrail = applyGuardrails(compiled, session);

            SemanticToolMeta meta = new SemanticToolMeta(
                    TOOL_CODE,
                    VERSION,
                    Boolean.TRUE.equals(guardrail.allowed()) ? 0.98d : 0.0d,
                    false,
                    null,
                    List.of()
            );

            SemanticCompiledSql compiledSql = new SemanticCompiledSql(compiled.sql(), compiled.params());
            SemanticQueryResponseV2 response = new SemanticQueryResponseV2(meta, ast, compiledSql, guardrail);

            Map<String, Object> outputPayload = new LinkedHashMap<>();
            outputPayload.put("tool", TOOL_CODE);
            outputPayload.put("version", VERSION);
            outputPayload.put("semantic_v2_stage", "query");
            outputPayload.put("semantic_v2_event", "output");
            outputPayload.put("question", question);
            outputPayload.put("confidence", response.meta() == null ? null : response.meta().confidence());
            outputPayload.put("needsClarification", response.meta() != null && Boolean.TRUE.equals(response.meta().needsClarification()));
            outputPayload.put("guardrailAllowed", response.guardrail() == null || Boolean.TRUE.equals(response.guardrail().allowed()));
            outputPayload.put("guardrailReason", response.guardrail() == null ? null : response.guardrail().reason());
            outputPayload.put("compiledSql", response.compiledSql());
            audit("SEMANTIC_QUERY_V2_OUTPUT", conversationId, outputPayload, session, false);

            if (response.guardrail() != null && !Boolean.TRUE.equals(response.guardrail().allowed())) {
                failureFeedbackService.recordFailure(new SemanticFailureRecord(
                        conversationId,
                        question,
                        compiled.sql(),
                        null,
                        "SEMANTIC_GUARDRAIL_BLOCKED",
                        response.guardrail().reason(),
                        "SEMANTIC_QUERY_V2",
                        Map.of(
                                "strictMode", strictMode,
                                "queryClass", safeText(plan.queryClass()),
                                "baseEntity", safeText(plan.baseEntity())
                        )
                ));
            }
            return response;
        } catch (RuntimeException ex) {
            Map<String, Object> errorPayload = new LinkedHashMap<>();
            errorPayload.put("tool", TOOL_CODE);
            errorPayload.put("version", VERSION);
            errorPayload.put("semantic_v2_stage", "query");
            errorPayload.put("semantic_v2_event", "error");
            errorPayload.put("question", question);
            errorPayload.put("errorClass", ex.getClass().getName());
            errorPayload.put("errorMessage", ex.getMessage());
            audit("SEMANTIC_QUERY_V2_ERROR", conversationId, errorPayload, session, true);

            failureFeedbackService.recordFailure(new SemanticFailureRecord(
                    conversationId,
                    question,
                    extractGeneratedSql(request),
                    null,
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    "SEMANTIC_QUERY_V2",
                    Map.of(
                            "strictMode", request != null && Boolean.TRUE.equals(request.strictMode()),
                            "dryRun", request != null && Boolean.TRUE.equals(request.dryRun())
                    )
            ));
            throw ex;
        }
    }

    private void validatePlan(ResolvedSemanticPlan plan, boolean strictMode) {
        if (plan.baseTable() == null || plan.baseTable().isBlank()) {
            throw new IllegalArgumentException("resolvedPlan.baseTable is required.");
        }
        if (!strictMode) {
            return;
        }

        if (plan.select() == null || plan.select().isEmpty()) {
            throw new IllegalArgumentException("strictMode=true requires resolvedPlan.select to be non-empty.");
        }
        for (ResolvedSelectItem item : plan.select()) {
            if (item == null || item.column() == null || item.column().isBlank()) {
                throw new IllegalArgumentException("strictMode=true requires every select item to have column mapping.");
            }
        }
        if (plan.filters() != null) {
            for (ResolvedFilter filter : plan.filters()) {
                if (filter == null || filter.column() == null || filter.column().isBlank()) {
                    throw new IllegalArgumentException("strictMode=true requires every filter to have column mapping.");
                }
            }
        }
    }

    private SemanticQueryAstV1 buildAst(ResolvedSemanticPlan plan) {
        List<String> select = new ArrayList<>();
        if (plan.select() != null) {
            for (ResolvedSelectItem item : plan.select()) {
                if (item == null || item.field() == null || item.field().isBlank()) {
                    continue;
                }
                select.add(item.field());
            }
        }

        List<AstFilter> conditions = new ArrayList<>();
        if (plan.filters() != null) {
            for (ResolvedFilter filter : plan.filters()) {
                if (filter == null || filter.field() == null || filter.field().isBlank()) {
                    continue;
                }
                conditions.add(new AstFilter(filter.field(), normalizeOp(filter.op()), filter.value()));
            }
        }
        AstFilterGroup where = new AstFilterGroup("AND", conditions, List.of());

        AstTimeRange timeRange = null;
        if (plan.timeRange() != null && plan.timeRange().column() != null) {
            timeRange = new AstTimeRange(plan.timeRange().column(), plan.timeRange().from(), plan.timeRange().to());
        }

        List<AstSort> sorts = new ArrayList<>();
        if (plan.sort() != null) {
            for (ResolvedSort sort : plan.sort()) {
                if (sort == null || sort.column() == null || sort.column().isBlank()) {
                    continue;
                }
                sorts.add(new AstSort(sort.column(), normalizeDirection(sort.direction())));
            }
        }

        return new SemanticQueryAstV1(
                "v1",
                plan.baseEntity(),
                select,
                List.of(),
                conditions,
                where,
                timeRange,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                sorts,
                null,
                plan.limit(),
                0,
                false,
                List.of()
        );
    }

    private CompiledSql compile(ResolvedSemanticPlan plan) {
        StringBuilder sql = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();

        appendSelect(sql, plan.select());
        sql.append(" FROM ").append(plan.baseTable());

        appendJoins(sql, plan.joins());

        int conditionCount = 0;
        conditionCount = appendFilters(sql, params, plan.filters(), conditionCount);
        conditionCount = appendTimeRange(sql, params, plan.timeRange(), conditionCount);

        appendSort(sql, plan.sort());

        if (plan.limit() != null && plan.limit() > 0) {
            sql.append(" LIMIT :p_limit");
            params.put("p_limit", plan.limit());
        }

        return new CompiledSql(sql.toString(), params);
    }

    private void appendSelect(StringBuilder sql, List<ResolvedSelectItem> select) {
        if (select == null || select.isEmpty()) {
            sql.append("SELECT *");
            return;
        }
        List<String> columns = new ArrayList<>();
        for (ResolvedSelectItem item : select) {
            if (item == null || item.column() == null || item.column().isBlank()) {
                continue;
            }
            columns.add(item.column());
        }
        if (columns.isEmpty()) {
            sql.append("SELECT *");
            return;
        }
        sql.append("SELECT ").append(String.join(", ", columns));
    }

    private void appendJoins(StringBuilder sql, List<ResolvedJoinPlan> joins) {
        if (joins == null || joins.isEmpty()) {
            return;
        }
        for (ResolvedJoinPlan join : joins) {
            if (join == null || join.rightTable() == null || join.rightTable().isBlank() || join.on() == null || join.on().isBlank()) {
                continue;
            }
            String joinType = join.joinType() == null || join.joinType().isBlank()
                    ? "LEFT"
                    : join.joinType().trim().toUpperCase(Locale.ROOT);
            sql.append(" ").append(joinType).append(" JOIN ").append(join.rightTable()).append(" ON ").append(join.on());
        }
    }

    private int appendFilters(StringBuilder sql,
                              Map<String, Object> params,
                              List<ResolvedFilter> filters,
                              int conditionCount) {
        if (filters == null || filters.isEmpty()) {
            return conditionCount;
        }
        for (int i = 0; i < filters.size(); i++) {
            ResolvedFilter filter = filters.get(i);
            if (filter == null || filter.column() == null || filter.column().isBlank()) {
                continue;
            }
            String op = normalizeOp(filter.op());
            String param = "p_f_" + (conditionCount + 1);
            String clause = buildFilterClause(filter.column(), op, param, filter.value(), params);
            if (clause == null || clause.isBlank()) {
                continue;
            }
            sql.append(conditionCount == 0 ? " WHERE " : " AND ").append(clause);
            conditionCount++;
        }
        return conditionCount;
    }

    private int appendTimeRange(StringBuilder sql,
                                Map<String, Object> params,
                                ResolvedTimeRange timeRange,
                                int conditionCount) {
        if (timeRange == null || timeRange.column() == null || timeRange.column().isBlank()) {
            return conditionCount;
        }
        if (timeRange.from() != null && !timeRange.from().isBlank()) {
            sql.append(conditionCount == 0 ? " WHERE " : " AND ")
                    .append(timeRange.column()).append(" >= :p_time_from");
            params.put("p_time_from", timeRange.from());
            conditionCount++;
        }
        if (timeRange.to() != null && !timeRange.to().isBlank()) {
            sql.append(conditionCount == 0 ? " WHERE " : " AND ")
                    .append(timeRange.column()).append(" <= :p_time_to");
            params.put("p_time_to", timeRange.to());
            conditionCount++;
        }
        return conditionCount;
    }

    private void appendSort(StringBuilder sql, List<ResolvedSort> sort) {
        if (sort == null || sort.isEmpty()) {
            return;
        }
        List<String> clauses = new ArrayList<>();
        for (ResolvedSort item : sort) {
            if (item == null || item.column() == null || item.column().isBlank()) {
                continue;
            }
            clauses.add(item.column() + " " + normalizeDirection(item.direction()));
        }
        if (clauses.isEmpty()) {
            return;
        }
        sql.append(" ORDER BY ").append(String.join(", ", clauses));
    }

    @SuppressWarnings("unchecked")
    private String buildFilterClause(String column,
                                     String op,
                                     String param,
                                     Object value,
                                     Map<String, Object> params) {
        return switch (op) {
            case "IS_NULL" -> column + " IS NULL";
            case "IS_NOT_NULL" -> column + " IS NOT NULL";
            case "IN", "NOT_IN" -> {
                List<Object> values;
                if (value instanceof List<?> list) {
                    values = new ArrayList<>((List<Object>) list);
                } else if (value == null) {
                    values = List.of();
                } else {
                    values = List.of(value);
                }
                params.put(param, values);
                yield column + ("NOT_IN".equals(op) ? " NOT IN " : " IN ") + "(:" + param + ")";
            }
            case "BETWEEN" -> {
                if (value instanceof List<?> list && list.size() >= 2) {
                    String p1 = param + "_from";
                    String p2 = param + "_to";
                    params.put(p1, list.get(0));
                    params.put(p2, list.get(1));
                    yield column + " BETWEEN :" + p1 + " AND :" + p2;
                }
                params.put(param, value);
                yield column + " = :" + param;
            }
            case "LIKE", "ILIKE", "EQ", "NE", "GT", "GTE", "LT", "LTE" -> {
                params.put(param, value);
                yield column + " " + toSqlOperator(op) + " :" + param;
            }
            default -> {
                params.put(param, value);
                yield column + " = :" + param;
            }
        };
    }

    private SemanticGuardrailResult applyGuardrails(CompiledSql compiledSql, EngineSession session) {
        List<SemanticSqlPolicyValidator> validators = validatorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(validators);
        SemanticQueryContext context = new SemanticQueryContext(session == null ? "" : session.getUserText(), session);
        try {
            for (SemanticSqlPolicyValidator validator : validators) {
                if (validator == null || !validator.supports(context)) {
                    continue;
                }
                validator.validate(compiledSql, context);
            }
            return new SemanticGuardrailResult(true, null);
        } catch (Exception ex) {
            return new SemanticGuardrailResult(false, ex.getMessage());
        }
    }

    private String normalizeOp(String op) {
        if (op == null || op.isBlank()) {
            return "EQ";
        }
        String normalized = op.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "=", "EQ", "EQUALS" -> "EQ";
            case "!=", "<>", "NE" -> "NE";
            case ">", "GT" -> "GT";
            case ">=", "GTE" -> "GTE";
            case "<", "LT" -> "LT";
            case "<=", "LTE" -> "LTE";
            case "IN" -> "IN";
            case "NOT_IN" -> "NOT_IN";
            case "LIKE" -> "LIKE";
            case "ILIKE" -> "ILIKE";
            case "BETWEEN" -> "BETWEEN";
            case "IS_NULL" -> "IS_NULL";
            case "IS_NOT_NULL" -> "IS_NOT_NULL";
            default -> "EQ";
        };
    }

    private String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return "DESC";
        }
        return "ASC".equalsIgnoreCase(direction.trim()) ? "ASC" : "DESC";
    }

    private String toSqlOperator(String op) {
        return switch (op) {
            case "EQ" -> "=";
            case "NE" -> "!=";
            case "GT" -> ">";
            case "GTE" -> ">=";
            case "LT" -> "<";
            case "LTE" -> "<=";
            case "LIKE" -> "LIKE";
            case "ILIKE" -> "ILIKE";
            default -> "=";
        };
    }

    private void audit(String stage,
                       UUID conversationId,
                       Map<String, Object> payload,
                       EngineSession session,
                       boolean error) {
        if (conversationId != null) {
            auditService.audit(stage, conversationId, payload == null ? Map.of() : payload);
        }
        if (session != null && verbosePublisher != null) {
            verbosePublisher.publish(session,
                    "SemanticQueryV2Service",
                    stage,
                    null,
                    TOOL_CODE,
                    error,
                    payload == null ? Map.of() : payload);
        }
    }

    private UUID safeConversationId(SemanticQueryRequestV2 request, EngineSession session) {
        if (request != null && request.conversationId() != null && !request.conversationId().isBlank()) {
            try {
                return UUID.fromString(request.conversationId().trim());
            } catch (Exception ignored) {
            }
        }
        return session == null ? null : session.getConversationId();
    }

    private String extractGeneratedSql(SemanticQueryRequestV2 request) {
        if (request == null || request.resolvedPlan() == null) {
            return null;
        }
        try {
            return compile(request.resolvedPlan()).sql();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }
}
