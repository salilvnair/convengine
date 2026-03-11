package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.OperatorHandlerContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultWithinLastAstOperatorHandlerTest {

    @Test
    void buildSqlUsesGreaterThanOrEqualAndBindsCutoffTimestamp() {
        DefaultWithinLastAstOperatorHandler handler = new DefaultWithinLastAstOperatorHandler();
        Map<String, Object> params = new LinkedHashMap<>();
        OperatorHandlerContext ctx = new OperatorHandlerContext(params, new int[]{1}, v -> v);

        String sql = handler.buildSql(
                DSL.field(DSL.name("t0", "requested_at")),
                "24h",
                AstOperator.WITHIN_LAST,
                ctx
        );

        assertTrue(sql.contains(" >= :p1"));
        assertTrue(params.containsKey("p1"));
        assertInstanceOf(OffsetDateTime.class, params.get("p1"));
    }

    @Test
    void supportsMapInputForAmountAndUnit() {
        DefaultWithinLastAstOperatorHandler handler = new DefaultWithinLastAstOperatorHandler();
        Map<String, Object> params = new LinkedHashMap<>();
        OperatorHandlerContext ctx = new OperatorHandlerContext(params, new int[]{1}, v -> v);

        Map<String, Object> value = Map.of("amount", 2, "unit", "day");
        handler.buildSql(DSL.field(DSL.name("t0", "requested_at")), value, AstOperator.WITHIN_LAST, ctx);

        assertEquals(1, params.size());
        assertInstanceOf(OffsetDateTime.class, params.get("p1"));
    }
}
