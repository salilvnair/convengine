package com.github.salilvnair.convengine.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableIntrospectionMatcherTest {

    @Test
    void matchesExactAndWildcardPatterns() {
        List<String> rules = TableIntrospectionMatcher.normalizePatterns(List.of("ce_*", "zp_request", "  "));
        assertTrue(TableIntrospectionMatcher.matches("ce_config", rules));
        assertTrue(TableIntrospectionMatcher.matches("ce_mcp_tool", rules));
        assertTrue(TableIntrospectionMatcher.matches("zp_request", rules));
        assertFalse(TableIntrospectionMatcher.matches("zp_connection", rules));
    }

    @Test
    void matchesQueryByMode() {
        assertTrue(TableIntrospectionMatcher.matchesQuery("ce_config", "ce_config", "EXACT"));
        assertFalse(TableIntrospectionMatcher.matchesQuery("ce_config", "ce_", "EXACT"));
        assertTrue(TableIntrospectionMatcher.matchesQuery("ce_config", "^ce_.*", "REGEX"));
        assertTrue(TableIntrospectionMatcher.matchesQuery("ce_config", "ce_", "REGEX"));
    }
}

