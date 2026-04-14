package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticModelDynamicOverlayServiceTest {

    @Test
    void applyMergesJoinHintsValuePatternsEntitiesAndRelationshipsFromDb() {
        SemanticModel base = new SemanticModel(
                1,
                "demo_ops",
                "base",
                Map.of(
                        "REQUEST", new SemanticEntity(
                                "Base request entity",
                                List.of("request"),
                                new SemanticEntityTables("zp_disco_request", List.of("zp_disco_trans_data")),
                                Map.of("requestId", new SemanticField("zp_disco_request.request_id", "string", null, true, true, true))
                        )
                ),
                List.of(new SemanticRelationship(
                        "request_to_ui",
                        "base rel",
                        new SemanticRelationshipEnd("zp_disco_request", "request_id"),
                        new SemanticRelationshipEnd("zp_disco_trans_data", "request_id"),
                        "one_to_one"
                )),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                Map.of("zp_disco_request", new SemanticJoinHint(List.of("zp_disco_trans_data"))),
                null,
                List.of(new SemanticIntentFieldRemap("requestId", "disconnectOrderId", List.of("DON"))),
                Map.of()
        );

        FakeNamedParameterJdbcTemplate jdbc = new FakeNamedParameterJdbcTemplate(Map.of(
                "ce_semantic_join_hint", List.of(
                        Map.of("base_table", "zp_disco_request", "join_table", "zp_disco_trans_data", "enabled", true),
                        Map.of("base_table", "zp_disco_request", "join_table", "zp_billbank_record", "enabled", 1)
                ),
                "ce_semantic_value_pattern", List.of(
                        Map.of("from_field", "requestId", "to_field", "disconnectOrderId", "value_starts_with", "DON|DOR", "enabled", true),
                        Map.of("from_field", "disconnectOrderId", "to_field", "requestId", "value_starts_with", "ZPR", "enabled", true)
                ),
                "ce_semantic_entity", List.of(
                        Map.of(
                                "entity_name", "REQUEST",
                                "description", "DB override request",
                                "primary_table", "zp_disco_request",
                                "related_tables", "zp_disco_trans_data,zp_billbank_record",
                                "synonyms", "request,order",
                                "fields_json", "{\"status\":{\"column\":\"zp_disco_request.request_status\",\"type\":\"string\",\"filterable\":true,\"searchable\":true,\"key\":false}}",
                                "enabled", true
                        )
                ),
                "ce_semantic_relationship", List.of(
                        Map.of(
                                "relationship_name", "request_to_disconnect",
                                "description", "db rel",
                                "from_table", "zp_disco_request",
                                "from_column", "request_id",
                                "to_table", "zp_disco_trans_data",
                                "to_column", "request_id",
                                "relation_type", "one_to_many",
                                "enabled", true
                        )
                )
        ));

        ObjectProvider<NamedParameterJdbcTemplate> provider = providerOf(jdbc);
        SemanticModelDynamicOverlayService service = new SemanticModelDynamicOverlayService(provider);

        SemanticModel merged = service.apply(base);

        assertNotNull(merged);
        assertNotNull(merged.joinHints().get("zp_disco_request"));
        assertEquals(List.of("zp_disco_trans_data", "zp_billbank_record"),
                merged.joinHints().get("zp_disco_request").commonlyJoinedWith());

        assertEquals(2, merged.valuePatterns().size());
        assertTrue(merged.valuePatterns().stream().anyMatch(v ->
                "requestId".equals(v.fromField())
                        && "disconnectOrderId".equals(v.toField())
                        && v.valueStartsWith().containsAll(List.of("DON", "DOR"))));

        SemanticEntity request = merged.entities().get("REQUEST");
        assertNotNull(request);
        assertEquals("DB override request", request.description());
        assertEquals(List.of("request", "order"), request.synonyms());
        assertEquals("zp_disco_request", request.tables().primary());
        assertEquals(List.of("zp_disco_trans_data", "zp_billbank_record"), request.tables().related());
        assertTrue(request.fields().containsKey("status"));

        assertTrue(merged.relationships().stream().anyMatch(r ->
                "request_to_disconnect".equals(r.name())
                        && "zp_disco_request".equals(r.from().table())
                        && "zp_disco_trans_data".equals(r.to().table())));
    }

    @Test
    void applyReturnsBaseModelWhenJdbcTemplateUnavailable() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ObjectProvider<NamedParameterJdbcTemplate> provider = factory.getBeanProvider(NamedParameterJdbcTemplate.class);
        SemanticModelDynamicOverlayService service = new SemanticModelDynamicOverlayService(provider);

        SemanticModel base = new SemanticModel(1, "db", "desc", Map.of(), List.of(), Map.of(), Map.of(), Map.of());
        SemanticModel out = service.apply(base);

        assertSame(base, out);
    }

    private ObjectProvider<NamedParameterJdbcTemplate> providerOf(NamedParameterJdbcTemplate jdbc) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("jdbcTemplate", jdbc);
        return factory.getBeanProvider(NamedParameterJdbcTemplate.class);
    }

    private static final class FakeNamedParameterJdbcTemplate extends NamedParameterJdbcTemplate {
        private final Map<String, List<Map<String, Object>>> rowsByTable;

        private FakeNamedParameterJdbcTemplate(Map<String, List<Map<String, Object>>> rowsByTable) {
            super(new JdbcTemplate());
            this.rowsByTable = new LinkedHashMap<>(rowsByTable);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Map<String, ?> paramMap) {
            String lower = sql == null ? "" : sql.toLowerCase();
            if (lower.contains("ce_semantic_join_hint")) {
                return rowsByTable.getOrDefault("ce_semantic_join_hint", List.of());
            }
            if (lower.contains("ce_semantic_value_pattern")) {
                return rowsByTable.getOrDefault("ce_semantic_value_pattern", List.of());
            }
            if (lower.contains("ce_semantic_entity")) {
                return rowsByTable.getOrDefault("ce_semantic_entity", List.of());
            }
            if (lower.contains("ce_semantic_relationship")) {
                return rowsByTable.getOrDefault("ce_semantic_relationship", List.of());
            }
            return List.of();
        }
    }
}
