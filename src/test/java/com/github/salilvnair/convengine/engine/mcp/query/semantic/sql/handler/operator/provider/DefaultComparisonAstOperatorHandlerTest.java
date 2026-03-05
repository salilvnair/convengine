package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.OperatorHandlerContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultComparisonAstOperatorHandlerTest {

    @Test
    void ilikeWithoutWildcardIsNormalizedToContainsPattern() {
        DefaultComparisonAstOperatorHandler handler = new DefaultComparisonAstOperatorHandler();
        Map<String, Object> params = new LinkedHashMap<>();
        OperatorHandlerContext ctx = new OperatorHandlerContext(params, new int[]{1}, v -> v);

        String sql = handler.buildSql(DSL.field(DSL.name("t0", "team_notes")), "billing", AstOperator.ILIKE, ctx);

        assertTrue(sql.contains(" ILIKE :p1"));
        assertEquals("%billing%", params.get("p1"));
    }

    @Test
    void ilikeWithExistingWildcardIsPreserved() {
        DefaultComparisonAstOperatorHandler handler = new DefaultComparisonAstOperatorHandler();
        Map<String, Object> params = new LinkedHashMap<>();
        OperatorHandlerContext ctx = new OperatorHandlerContext(params, new int[]{1}, v -> v);

        handler.buildSql(DSL.field(DSL.name("t0", "team_notes")), "%billing%", AstOperator.ILIKE, ctx);

        assertEquals("%billing%", params.get("p1"));
    }
}

