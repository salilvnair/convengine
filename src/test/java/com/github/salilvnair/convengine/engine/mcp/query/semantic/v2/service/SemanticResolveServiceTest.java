package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntityTables;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelDynamicOverlayService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelLoader;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticRelationship;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticRelationshipEnd;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.CanonicalIntent;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticResolveRequest;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticResolveResponse;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticTimeRange;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticResolveServiceTest {

    private ConvEngineMcpConfig config;
    private SemanticModelRegistry registry;

    @BeforeEach
    void setUp() {
        config = new ConvEngineMcpConfig();
        config.getDb().getSemantic().setEnabled(true);
        config.getDb().getSemantic().setTimezone("America/Chicago");
        config.getDb().getSemantic().setDefaultLimit(100);
        config.getDb().getSemantic().setMaxLimit(500);
        config.getDb().getSemantic().getClarification().setConfidenceThreshold(0.80d);

        SemanticModelLoader loader = new SemanticModelLoader(config, new DefaultResourceLoader());
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ObjectProvider<NamedParameterJdbcTemplate> noJdbc = factory.getBeanProvider(NamedParameterJdbcTemplate.class);
        SemanticModelDynamicOverlayService overlay = new SemanticModelDynamicOverlayService(noJdbc);
        registry = new SemanticModelRegistry(loader, overlay, new SemanticModelValidator());

        registry.setModel(new SemanticModel(
                1,
                "demo_ops",
                "test",
                Map.of(
                        "REQUEST", new SemanticEntity(
                                "Request",
                                List.of("request"),
                                new SemanticEntityTables("zp_disco_request", List.of("zp_disco_trans_data")),
                                Map.of(
                                        "requestId", new SemanticField("zp_disco_request.request_id", "string", null, true, true, true),
                                        "status", new SemanticField("zp_disco_request.request_status", "string", null, true, true, false),
                                        "created_at", new SemanticField("zp_disco_request.created_at", "timestamp", null, true, true, false),
                                        "customer", new SemanticField("zp_disco_request.customer_name", "string", null, true, true, false)
                                )
                        )
                ),
                List.of(new SemanticRelationship(
                        "request_to_disconnect",
                        "rel",
                        new SemanticRelationshipEnd("zp_disco_request", "request_id"),
                        new SemanticRelationshipEnd("zp_disco_trans_data", "request_id"),
                        "one_to_many"
                )),
                Map.of(),
                Map.of(),
                Map.of()
        ));
    }

    @Test
    void resolvesCanonicalIntentDeterministicallyFromDbMappings() {
        FakeNamedParameterJdbcTemplate jdbc = new FakeNamedParameterJdbcTemplate(Map.of(
                "ce_semantic_mapping", List.of(
                        Map.of(
                                "concept_key", "DISCONNECT_REQUEST",
                                "entity_key", "REQUEST",
                                "field_key", "requestId",
                                "mapped_table", "zp_disco_request",
                                "mapped_column", "request_id",
                                "operator_type", "EQ",
                                "value_map_json", "{}",
                                "query_class_key", "LIST_REQUESTS",
                                "priority", 10
                        ),
                        Map.of(
                                "concept_key", "DISCONNECT_REQUEST",
                                "entity_key", "REQUEST",
                                "field_key", "status",
                                "mapped_table", "zp_disco_request",
                                "mapped_column", "request_status",
                                "operator_type", "EQ",
                                "value_map_json", "{}",
                                "query_class_key", "LIST_REQUESTS",
                                "priority", 11
                        ),
                        Map.of(
                                "concept_key", "DISCONNECT_REQUEST",
                                "entity_key", "REQUEST",
                                "field_key", "customer",
                                "mapped_table", "zp_disco_request",
                                "mapped_column", "customer_name",
                                "operator_type", "EQ",
                                "value_map_json", "{}",
                                "query_class_key", "LIST_REQUESTS",
                                "priority", 12
                        ),
                        Map.of(
                                "concept_key", "DISCONNECT_REQUEST",
                                "entity_key", "REQUEST",
                                "field_key", "created_at",
                                "mapped_table", "zp_disco_request",
                                "mapped_column", "created_at",
                                "operator_type", "EQ",
                                "value_map_json", "{}",
                                "query_class_key", "LIST_REQUESTS",
                                "priority", 13
                        )
                ),
                "ce_semantic_query_class", List.of(
                        Map.of(
                                "query_class_key", "LIST_REQUESTS",
                                "base_table_name", "zp_disco_request",
                                "default_select_fields_json", "[\"requestId\",\"customer\",\"status\"]",
                                "default_sort_fields_json", "[\"created_at DESC\"]"
                        )
                )
        ));
        ObjectProvider<NamedParameterJdbcTemplate> jdbcProvider = providerOf(jdbc);

        SemanticResolveService service = new SemanticResolveService(
                config,
                registry,
                new SemanticResolveMappingValidator(),
                jdbcProvider,
                new NoopAuditService(),
                null
        );

        CanonicalIntent canonicalIntent = new CanonicalIntent(
                "LIST_REQUESTS",
                "REQUEST",
                "LIST_REQUESTS",
                List.of(
                        new SemanticFilter("status", "EQ", "FAILED"),
                        new SemanticFilter("customer", "EQ", "UPS")
                ),
                new SemanticTimeRange("RELATIVE", "TODAY", "America/Chicago", null, null),
                List.of(new SemanticSort("created_at", "DESC")),
                100
        );

        SemanticResolveResponse response = service.resolve(
                new SemanticResolveRequest(canonicalIntent, UUID.randomUUID().toString(), Map.of()),
                session("show failed requests for ups today")
        );

        assertNotNull(response.resolvedPlan());
        assertEquals("zp_disco_request", response.resolvedPlan().baseTable());
        assertEquals(2, response.resolvedPlan().filters().size());
        assertEquals("zp_disco_request.request_status", response.resolvedPlan().filters().getFirst().column());
        assertNotNull(response.resolvedPlan().timeRange());
        assertTrue(response.unresolved().isEmpty());
        assertFalse(response.meta().needsClarification());
    }

    @Test
    void returnsUnresolvedInsteadOfGuessingUnknownField() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ObjectProvider<NamedParameterJdbcTemplate> noJdbc = factory.getBeanProvider(NamedParameterJdbcTemplate.class);

        SemanticResolveService service = new SemanticResolveService(
                config,
                registry,
                new SemanticResolveMappingValidator(),
                noJdbc,
                new NoopAuditService(),
                null
        );

        CanonicalIntent canonicalIntent = new CanonicalIntent(
                "LIST_REQUESTS",
                "REQUEST",
                "LIST_REQUESTS",
                List.of(new SemanticFilter("accountId", "EQ", "UPS100")),
                null,
                List.of(),
                100
        );

        EngineSession session = session("show requests for account UPS100");
        SemanticResolveResponse response = service.resolve(
                new SemanticResolveRequest(canonicalIntent, UUID.randomUUID().toString(), Map.of()),
                session
        );

        assertTrue(response.unresolved().stream().anyMatch(u -> "FILTER_FIELD".equals(u.type()) && "accountId".equals(u.field())));
        assertTrue(response.meta().needsClarification());
        assertNotNull(response.meta().clarificationQuestion());
        assertTrue(session.hasPendingClarification());
    }

    @Test
    void resolvesUsingDbMappingsAndValueMapping() {
        FakeNamedParameterJdbcTemplate jdbc = new FakeNamedParameterJdbcTemplate(Map.of(
                "ce_semantic_mapping", List.of(
                        Map.of(
                                "concept_key", "FAILED",
                                "entity_key", "REQUEST",
                                "field_key", "status",
                                "mapped_table", "zp_disco_request",
                                "mapped_column", "request_status",
                                "operator_type", "IN",
                                "value_map_json", "{\"FAILED\":[\"FAILED\",\"ERROR\"]}",
                                "query_class_key", "LIST_REQUESTS",
                                "priority", 100
                        )
                )
        ));
        ObjectProvider<NamedParameterJdbcTemplate> jdbcProvider = providerOf(jdbc);

        SemanticResolveService service = new SemanticResolveService(
                config,
                registry,
                new SemanticResolveMappingValidator(),
                jdbcProvider,
                new NoopAuditService(),
                null
        );

        CanonicalIntent canonicalIntent = new CanonicalIntent(
                "LIST_REQUESTS",
                "REQUEST",
                "LIST_REQUESTS",
                List.of(new SemanticFilter("status", "EQ", "FAILED")),
                null,
                List.of(),
                100
        );

        SemanticResolveResponse response = service.resolve(
                new SemanticResolveRequest(canonicalIntent, UUID.randomUUID().toString(), Map.of()),
                session("show failed requests")
        );

        assertTrue(response.unresolved().isEmpty());
        assertEquals("IN", response.resolvedPlan().filters().getFirst().op());
        Object mapped = response.resolvedPlan().filters().getFirst().value();
        assertTrue(mapped instanceof List<?>);
        assertEquals(List.of("FAILED", "ERROR"), mapped);
    }

    private ObjectProvider<NamedParameterJdbcTemplate> providerOf(NamedParameterJdbcTemplate jdbc) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("jdbcTemplate", jdbc);
        return factory.getBeanProvider(NamedParameterJdbcTemplate.class);
    }

    private EngineSession session(String userText) {
        EngineContext context = EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(userText)
                .inputParams(new LinkedHashMap<>())
                .build();
        EngineSession session = new EngineSession(context, new ObjectMapper());
        session.setIntent("SEMANTIC_QUERY");
        session.setState("ANALYZE");
        return session;
    }

    private static final class FakeNamedParameterJdbcTemplate extends NamedParameterJdbcTemplate {
        private final Map<String, List<Map<String, Object>>> rowsByTable;

        private FakeNamedParameterJdbcTemplate(Map<String, List<Map<String, Object>>> rowsByTable) {
            super(new JdbcTemplate());
            this.rowsByTable = rowsByTable;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Map<String, ?> paramMap) {
            String lower = sql == null ? "" : sql.toLowerCase();
            if (lower.contains("ce_semantic_mapping")) {
                return rowsByTable.getOrDefault("ce_semantic_mapping", List.of());
            }
            if (lower.contains("ce_semantic_join_path")) {
                return rowsByTable.getOrDefault("ce_semantic_join_path", List.of());
            }
            if (lower.contains("ce_semantic_query_class")) {
                return rowsByTable.getOrDefault("ce_semantic_query_class", List.of());
            }
            return List.of();
        }
    }

    private static final class NoopAuditService implements AuditService {
        @Override
        public void audit(String stage, UUID conversationId, String payloadJson) {
            // no-op
        }
    }
}
