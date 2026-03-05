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

import java.util.List;

@Component
@Order()
public class DefaultBetweenAstOperatorHandler implements AstOperatorHandler {
    @Override
    public boolean supports(AstOperator operator) {
        return operator == AstOperator.BETWEEN;
    }

    @Override
    public Condition buildCondition(Field<Object> field, Object value, AstOperator operator, OperatorHandlerContext context) {
        if (!(value instanceof List<?> list) || list.size() < 2) {
            return DSL.trueCondition();
        }
        return DSL.condition("{0} BETWEEN {1} AND {2}", field, context.nextParam(list.get(0)), context.nextParam(list.get(1)));
    }

    @Override
    public String buildSql(Field<Object> field, Object value, AstOperator operator, OperatorHandlerContext context) {
        if (!(value instanceof List<?> list) || list.size() < 2) {
            return "";
        }
        String p1 = context.nextParamKey(list.get(0));
        String p2 = context.nextParamKey(list.get(1));
        return field + " BETWEEN :" + p1 + " AND :" + p2;
    }

    @Override
    public String sqlToken(AstOperator operator) {
        return "BETWEEN";
    }
}
