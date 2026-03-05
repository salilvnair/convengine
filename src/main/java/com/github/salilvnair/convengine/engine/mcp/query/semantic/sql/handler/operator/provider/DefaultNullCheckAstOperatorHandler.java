package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.AstOperatorHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.OperatorHandlerContext;
import org.jooq.Condition;
import org.jooq.Field;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order()
public class DefaultNullCheckAstOperatorHandler implements AstOperatorHandler {
    @Override
    public boolean supports(AstOperator operator) {
        return operator == AstOperator.IS_NULL || operator == AstOperator.IS_NOT_NULL;
    }

    @Override
    public Condition buildCondition(Field<Object> field, Object value, AstOperator operator, OperatorHandlerContext context) {
        return operator == AstOperator.IS_NULL ? field.isNull() : field.isNotNull();
    }

    @Override
    public String buildSql(Field<Object> field, Object value, AstOperator operator, OperatorHandlerContext context) {
        return field + " " + sqlToken(operator);
    }

    @Override
    public String sqlToken(AstOperator operator) {
        return operator == AstOperator.IS_NULL ? "IS NULL" : "IS NOT NULL";
    }
}
