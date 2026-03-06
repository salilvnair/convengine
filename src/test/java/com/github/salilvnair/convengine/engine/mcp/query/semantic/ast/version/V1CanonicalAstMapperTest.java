package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.version;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V1CanonicalAstMapperTest {

    private final V1CanonicalAstMapper mapper = new V1CanonicalAstMapper();

    @Test
    void mapsContainsOperatorToIlike() {
        SemanticQueryAstV1 ast = new SemanticQueryAstV1(
                "v1",
                "DisconnectRequest",
                List.of("teamNotes"),
                List.of(),
                List.of(),
                new AstFilterGroup("AND", List.of(new AstFilter("teamNotes", "contains", "billing")), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                100,
                0,
                false,
                List.of()
        );

        var canonical = mapper.from(ast);
        assertEquals(AstOperator.ILIKE, canonical.where().conditions().get(0).operator());
    }

    @Test
    void mapsIsNotOperatorVariantToIsNotNull() {
        SemanticQueryAstV1 ast = new SemanticQueryAstV1(
                "v1",
                "BillingRecord",
                List.of("billbankId"),
                List.of(),
                List.of(),
                new AstFilterGroup("AND", List.of(new AstFilter("billbankId", "IS NOT", null)), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                100,
                0,
                false,
                List.of()
        );

        var canonical = mapper.from(ast);
        assertEquals(AstOperator.IS_NOT_NULL, canonical.where().conditions().get(0).operator());
    }

    @Test
    void mapsNotInOperatorVariantWithSpace() {
        SemanticQueryAstV1 ast = new SemanticQueryAstV1(
                "v1",
                "DisconnectRequest",
                List.of("accountId"),
                List.of(),
                List.of(),
                new AstFilterGroup("AND", List.of(new AstFilter("accountId", "NOT IN", List.of("UPSA100"))), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                100,
                0,
                false,
                List.of()
        );

        var canonical = mapper.from(ast);
        assertEquals(AstOperator.NOT_IN, canonical.where().conditions().get(0).operator());
    }

    @Test
    void throwsForUnsupportedOperatorInsteadOfFallingBackToEq() {
        SemanticQueryAstV1 ast = new SemanticQueryAstV1(
                "v1",
                "DisconnectRequest",
                List.of("teamNotes"),
                List.of(),
                List.of(),
                new AstFilterGroup("AND", List.of(new AstFilter("teamNotes", "foobar", "billing")), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                100,
                0,
                false,
                List.of()
        );

        assertThrows(IllegalStateException.class, () -> mapper.from(ast));
    }
}
