package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AstOperatorNormalizationTest {

    @Test
    void mapsAggregateStyleOperatorsOnFilter() {
        assertEquals(AstOperator.GT, new AstFilter("x", "COUNT_GT", 1).operatorEnum());
        assertEquals(AstOperator.GTE, new AstFilter("x", "SUM_GTE", 1).operatorEnum());
        assertEquals(AstOperator.NOT_IN, new AstFilter("x", "COUNT_NOT_IN", java.util.List.of("a")).operatorEnum());
        assertEquals(AstOperator.IS_NOT_NULL, new AstFilter("x", "COUNT_NOTNULL", null).operatorEnum());
        assertEquals(AstOperator.WITHIN_LAST, new AstFilter("x", "within last", "24h").operatorEnum());
        assertEquals(AstOperator.WITHIN_LAST, new AstFilter("x", "SUM_WITHIN_LAST", "24h").operatorEnum());
    }

    @Test
    void mapsAggregateStyleOperatorsOnSubqueryFilter() {
        AstSubquerySpec subquery = new AstSubquerySpec("Account", "accountId", null, null, null, 1);
        assertEquals(AstOperator.GT, new AstSubqueryFilter("x", "COUNT_GT", subquery).operatorEnum());
        assertEquals(AstOperator.IS_NULL, new AstSubqueryFilter("x", "MAX_NULL", subquery).operatorEnum());
    }
}
