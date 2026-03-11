package com.github.salilvnair.convengine.engine.mcp.query.semantic.execute;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSemanticSqlExecutorTest {

    @Test
    void shouldNormalizeTemporalStringParams() {
        DefaultSemanticSqlExecutor executor = new DefaultSemanticSqlExecutor(null);

        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                executor,
                "normalizeParams",
                Map.of("p1", "2026-03-10T00:00:00Z", "p2", "2026-03-11", "__limit", 100)
        );

        assertInstanceOf(OffsetDateTime.class, normalized.get("p1"));
        assertInstanceOf(OffsetDateTime.class, normalized.get("p2"));
        assertEquals(100, normalized.get("__limit"));
    }

    @Test
    void shouldKeepNonTemporalStringParamsUnchanged() {
        DefaultSemanticSqlExecutor executor = new DefaultSemanticSqlExecutor(null);

        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                executor,
                "normalizeParams",
                Map.of("status", "FAILED_TERMINATION_FEE")
        );

        assertTrue(normalized.get("status") instanceof String);
        assertEquals("FAILED_TERMINATION_FEE", normalized.get("status"));
    }
}
