package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.AstOperatorHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.OperatorHandlerContext;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
@Order()
public class DefaultComparisonAstOperatorHandler implements AstOperatorHandler {
    private static final Set<AstOperator> SUPPORTED = EnumSet.of(
            AstOperator.EQ, AstOperator.NE, AstOperator.GT, AstOperator.GTE,
            AstOperator.LT, AstOperator.LTE, AstOperator.LIKE, AstOperator.ILIKE
    );

    @Override
    public boolean supports(AstOperator operator) {
        return operator != null && SUPPORTED.contains(operator);
    }

    @Override
    public Condition buildCondition(Field<Object> field, Object value, AstOperator operator, OperatorHandlerContext context) {
        return DSL.condition("{0} " + sqlToken(operator) + " {1}", field, context.nextParam(value));
    }

    @Override
    public String buildSql(Field<Object> field, Object value, AstOperator operator, OperatorHandlerContext context) {
        String key = context.nextParamKey(value);
        return field + " " + sqlToken(operator) + " :" + key;
    }

    @Override
    public String sqlToken(AstOperator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NE -> "<>";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case LIKE -> "LIKE";
            case ILIKE -> "ILIKE";
            default -> null;
        };
    }
}
