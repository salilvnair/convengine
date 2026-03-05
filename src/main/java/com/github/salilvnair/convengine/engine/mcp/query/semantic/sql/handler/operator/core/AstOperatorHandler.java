package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import org.jooq.Condition;
import org.jooq.Field;

public interface AstOperatorHandler {
    boolean supports(AstOperator operator);

    Condition buildCondition(Field<Object> field, Object value, AstOperator operator, OperatorHandlerContext context);

    String buildSql(Field<Object> field, Object value, AstOperator operator, OperatorHandlerContext context);

    default String sqlToken(AstOperator operator) {
        return null;
    }
}

