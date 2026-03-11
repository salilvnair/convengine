package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.normalize;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstExistsBlock;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntityTables;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticIntentExists;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticIntentFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticIntentRule;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAstNormalizerIntentRuleTest {

    @Test
    void prunesExistsBlockWhenOuterFieldIsNullConstrained() {
        DefaultAstNormalizer normalizer = new DefaultAstNormalizer(emptyInterceptorsProvider());
        SemanticQueryAstV1 ast = new SemanticQueryAstV1(
                "v1",
                "DisconnectRequest",
                List.of("requestId", "disconnectId"),
                List.of(),
                List.of(new AstFilter("disconnectId", "IS_NULL", null)),
                new AstFilterGroup("AND", List.of(new AstFilter("disconnectId", "IS_NULL", null)), List.of()),
                null,
                List.of(new AstExistsBlock(
                        "BillingRecord",
                        new AstFilterGroup("AND", List.of(new AstFilter("disconnectId", "EQ", "$disconnectId")), List.of()),
                        true
                )),
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

        SemanticQueryAstV1 normalized = normalizer.normalize(ast, modelWithoutIntentRule(), "DisconnectRequest", mockSession("show gaps"));
        assertTrue(normalized.existsBlocks().isEmpty(), "exists block should be pruned for null-correlated outer field");
    }

    @Test
    void injectsBillbankGapNotExistsConstraintFromIntentRule() {
        DefaultAstNormalizer normalizer = new DefaultAstNormalizer(emptyInterceptorsProvider());
        SemanticQueryAstV1 ast = new SemanticQueryAstV1(
                "v1",
                "DisconnectRequest",
                List.of("requestId", "disconnectId"),
                List.of(),
                List.of(),
                new AstFilterGroup("AND", List.of(), List.of()),
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

        SemanticQueryAstV1 normalized = normalizer.normalize(ast, modelWithBillbankGapRule(), "DisconnectRequest", mockSession("show billbank gap request order detail"));

        assertTrue(
                normalized.where().conditions().stream().anyMatch(f -> "disconnectId".equals(f.field()) && "IS_NOT_NULL".equalsIgnoreCase(f.op())),
                "intent rule should enforce disconnectId IS_NOT_NULL"
        );
        assertEquals(1, normalized.existsBlocks().size(), "intent rule should inject one exists block");
        AstExistsBlock existsBlock = normalized.existsBlocks().get(0);
        assertEquals("BillingRecord", existsBlock.entity());
        assertTrue(Boolean.TRUE.equals(existsBlock.notExists()));
        assertFalse(existsBlock.where().conditions().isEmpty());
        AstFilter filter = existsBlock.where().conditions().get(0);
        assertEquals("disconnectId", filter.field());
        assertEquals("EQ", filter.op());
        assertEquals("$disconnectId", filter.value());
    }

    private EngineSession mockSession(String question) {
        EngineContext context = EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(question)
                .inputParams(Map.of())
                .userInputParams(Map.of())
                .build();
        return new EngineSession(context, new ObjectMapper());
    }

    private SemanticModel modelWithoutIntentRule() {
        return new SemanticModel(
                1,
                "zapper_ops",
                "test",
                Map.of(
                        "DisconnectRequest", disconnectRequestEntity(),
                        "BillingRecord", billingRecordEntity()
                ),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    private SemanticModel modelWithBillbankGapRule() {
        SemanticIntentRule rule = new SemanticIntentRule(
                "billbank gap details",
                List.of("billbank gap"),
                List.of("request", "order", "detail"),
                "DisconnectRequest",
                "detail",
                true,
                List.of("requestId", "disconnectId"),
                List.of(new SemanticIntentFilter("disconnectId", "IS_NOT_NULL", null)),
                List.of(new SemanticIntentExists(
                        "BillingRecord",
                        true,
                        List.of(new SemanticIntentFilter("disconnectId", "EQ", "$disconnectId"))
                )),
                List.of()
        );
        return new SemanticModel(
                1,
                "zapper_ops",
                "test",
                Map.of(
                        "DisconnectRequest", disconnectRequestEntity(),
                        "BillingRecord", billingRecordEntity()
                ),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                null,
                null,
                null,
                Map.of("billbank_gap_details", rule)
        );
    }

    private SemanticEntity disconnectRequestEntity() {
        return new SemanticEntity(
                "Disconnect request",
                List.of(),
                new SemanticEntityTables("zp_request", List.of("zp_disconnect_order", "zp_ui_data")),
                Map.of(
                        "requestId", new SemanticField("zp_request.zp_request_id", "string", null, true, true, true),
                        "disconnectId", new SemanticField("zp_disconnect_order.zp_disconnect_id", "string", null, true, true, false)
                )
        );
    }

    private SemanticEntity billingRecordEntity() {
        return new SemanticEntity(
                "Billing record",
                List.of(),
                new SemanticEntityTables("zp_billbank_record", List.of()),
                Map.of(
                        "disconnectId", new SemanticField("zp_billbank_record.zp_disconnect_id", "string", null, true, true, false)
                )
        );
    }

    private ObjectProvider<List<AstNormalizeInterceptor>> emptyInterceptorsProvider() {
        return new ObjectProvider<>() {
            @Override
            public List<AstNormalizeInterceptor> getObject(Object... args) {
                return List.of();
            }

            @Override
            public List<AstNormalizeInterceptor> getIfAvailable() {
                return List.of();
            }

            @Override
            public List<AstNormalizeInterceptor> getIfUnique() {
                return List.of();
            }

            @Override
            public List<AstNormalizeInterceptor> getObject() {
                return List.of();
            }

            @Override
            public List<AstNormalizeInterceptor> getIfAvailable(Supplier<List<AstNormalizeInterceptor>> defaultSupplier) {
                return defaultSupplier == null ? List.of() : defaultSupplier.get();
            }

            @Override
            public List<AstNormalizeInterceptor> getIfUnique(Supplier<List<AstNormalizeInterceptor>> defaultSupplier) {
                return defaultSupplier == null ? List.of() : defaultSupplier.get();
            }

            @Override
            public Iterator<List<AstNormalizeInterceptor>> iterator() {
                return List.<List<AstNormalizeInterceptor>>of(List.of()).iterator();
            }
        };
    }
}
