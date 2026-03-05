package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.AstOperatorHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.OperatorHandlerContext;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Param;
import org.jooq.impl.DSL;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Order()
public class DefaultInAstOperatorHandler implements AstOperatorHandler {
    @Override
    public boolean supports(AstOperator operator) {
        return operator == AstOperator.IN || operator == AstOperator.NOT_IN;
    }

    @Override
    public Condition buildCondition(Field<Object> field, Object value, AstOperator operator, OperatorHandlerContext context) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return DSL.trueCondition();
        }
        List<Field<?>> params = new ArrayList<>();
        for (Object item : list) {
            Param<Object> p = context.nextParam(item);
            params.add(p);
        }
        return operator == AstOperator.NOT_IN ? field.notIn(params) : field.in(params);
    }

    @Override
    public String buildSql(Field<Object> field, Object value, AstOperator operator, OperatorHandlerContext context) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        List<String> placeholders = new ArrayList<>();
        for (Object item : list) {
            placeholders.add(":" + context.nextParamKey(item));
        }
        String keyword = operator == AstOperator.NOT_IN ? "NOT IN" : "IN";
        return field + " " + keyword + " (" + String.join(", ", placeholders) + ")";
    }

    @Override
    public String sqlToken(AstOperator operator) {
        return operator == AstOperator.NOT_IN ? "NOT IN" : "IN";
    }
}
