package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalProjection;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstLogicalOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntityTables;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAstValidatorWithinLastTest {

    @Test
    void acceptsWithinLastForTimestampField() {
        DefaultAstValidator validator = new DefaultAstValidator(config(), emptyInterceptorsProvider());
        CanonicalAst ast = new CanonicalAst(
                "v1",
                "DisconnectRequest",
                List.of(new CanonicalProjection("requestId", null)),
                new CanonicalFilterGroup(AstLogicalOperator.AND, List.of(
                        new CanonicalFilter("requestedAt", AstOperator.WITHIN_LAST, "24h")
                ), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                100,
                0,
                false,
                List.of()
        );

        AstValidationResult result = validator.validate(ast, model(), null, null);
        assertTrue(result.valid(), "WITHIN_LAST on timestamp field should be valid");
    }

    @Test
    void rejectsWithinLastForStringField() {
        DefaultAstValidator validator = new DefaultAstValidator(config(), emptyInterceptorsProvider());
        CanonicalAst ast = new CanonicalAst(
                "v1",
                "DisconnectRequest",
                List.of(new CanonicalProjection("requestId", null)),
                new CanonicalFilterGroup(AstLogicalOperator.AND, List.of(
                        new CanonicalFilter("requestStatus", AstOperator.WITHIN_LAST, "24h")
                ), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                100,
                0,
                false,
                List.of()
        );

        AstValidationResult result = validator.validate(ast, model(), null, null);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("operator WITHIN_LAST requires date/timestamp field")));
    }

    private ConvEngineMcpConfig config() {
        ConvEngineMcpConfig config = new ConvEngineMcpConfig();
        config.getDb().getSemantic().setMaxLimit(500);
        return config;
    }

    private SemanticModel model() {
        return new SemanticModel(
                1,
                "zapper_ops",
                "test",
                Map.of(
                        "DisconnectRequest",
                        new SemanticEntity(
                                "Disconnect diagnostics",
                                List.of(),
                                new SemanticEntityTables("zp_request", List.of()),
                                Map.of(
                                        "requestId", new SemanticField("zp_request.zp_request_id", "string", null, true, true, true),
                                        "requestStatus", new SemanticField("zp_request.request_status", "string", null, true, true, false),
                                        "requestedAt", new SemanticField("zp_request.requested_at", "timestamp", null, true, true, false)
                                )
                        )
                ),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    private ObjectProvider<List<AstValidationInterceptor>> emptyInterceptorsProvider() {
        return new ObjectProvider<>() {
            @Override
            public List<AstValidationInterceptor> getObject(Object... args) {
                return List.of();
            }

            @Override
            public List<AstValidationInterceptor> getIfAvailable() {
                return List.of();
            }

            @Override
            public List<AstValidationInterceptor> getIfUnique() {
                return List.of();
            }

            @Override
            public List<AstValidationInterceptor> getObject() {
                return List.of();
            }

            @Override
            public List<AstValidationInterceptor> getIfAvailable(Supplier<List<AstValidationInterceptor>> defaultSupplier) {
                return defaultSupplier == null ? List.of() : defaultSupplier.get();
            }

            @Override
            public List<AstValidationInterceptor> getIfUnique(Supplier<List<AstValidationInterceptor>> defaultSupplier) {
                return defaultSupplier == null ? List.of() : defaultSupplier.get();
            }

            @Override
            public Iterator<List<AstValidationInterceptor>> iterator() {
                return List.<List<AstValidationInterceptor>>of(List.of()).iterator();
            }
        };
    }
}
