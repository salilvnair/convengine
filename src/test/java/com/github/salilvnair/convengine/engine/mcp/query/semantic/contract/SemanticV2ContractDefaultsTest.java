package com.github.salilvnair.convengine.engine.mcp.query.semantic.contract;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticV2ContractDefaultsTest {

    @Test
    void canonicalIntentDefensivelyCopiesLists() {
        List<SemanticFilter> filters = new ArrayList<>();
        filters.add(new SemanticFilter("status", "EQ", "FAILED"));
        List<SemanticSort> sort = new ArrayList<>();
        sort.add(new SemanticSort("created_at", "DESC"));

        CanonicalIntent intent = new CanonicalIntent("LIST_REQUESTS", "REQUEST", "LIST_REQUESTS", filters, null, sort, 100);

        filters.add(new SemanticFilter("customer", "EQ", "UPS"));
        sort.clear();

        assertEquals(1, intent.filters().size());
        assertEquals(1, intent.sort().size());
    }

    @Test
    void toolMetaDefaultsAmbiguitiesToEmptyWhenNull() {
        SemanticToolMeta meta = new SemanticToolMeta("db.semantic.interpret", "v2", 0.9d, false, null, null, true, false, null);
        assertTrue(meta.ambiguities().isEmpty());
    }

    @Test
    void interpretResponseDefaultsCollections() {
        SemanticInterpretResponse interpret = new SemanticInterpretResponse(
                new SemanticToolMeta("db.semantic.interpret", "v2", 0.8d, false, null, null, true, false, null),
                "q",
                new CanonicalIntent("LIST_REQUESTS", "REQUEST", "LIST_REQUESTS", null, null, null, null),
                null
        );

        assertTrue(interpret.canonicalIntent().filters().isEmpty());
        assertTrue(interpret.canonicalIntent().sort().isEmpty());
    }
}
