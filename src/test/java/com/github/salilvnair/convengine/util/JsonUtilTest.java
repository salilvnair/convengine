package com.github.salilvnair.convengine.util;

import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilTest {

    @Test
    void parseOrNullReturnsNullNodeForInvalidJson() {
        assertEquals(NullNode.getInstance(), JsonUtil.parseOrNull("{bad json"));
    }

    @Test
    void mergePreservesExistingValuesWhenSourceHasNull() {
        String merged = JsonUtil.merge(
                "{\"customerId\":\"1234\",\"amount\":350000}",
                "{\"amount\":null,\"tenureMonths\":24}");

        assertEquals("{\"customerId\":\"1234\",\"amount\":350000,\"tenureMonths\":24}", merged);
    }

    @Test
    void schemaCompletenessHonorsRequiredFields() {
        String schema = """
                {
                  "type":"object",
                  "required":["customerId","requestedAmount"],
                  "properties":{
                    "customerId":{"type":"string"},
                    "requestedAmount":{"type":"number"}
                  }
                }
                """;

        assertTrue(JsonUtil.isSchemaComplete(schema, "{\"customerId\":\"1234\",\"requestedAmount\":350000}"));
        assertFalse(JsonUtil.isSchemaComplete(schema, "{\"customerId\":\"1234\"}"));
    }

    @Test
    void hasAnySchemaValueDetectsPresentNonBlankValues() {
        String schema = """
                {
                  "type":"object",
                  "properties":{
                    "customerId":{"type":"string"},
                    "requestedAmount":{"type":"number"}
                  }
                }
                """;

        assertTrue(JsonUtil.hasAnySchemaValue("{\"customerId\":\"1234\"}", schema));
        assertFalse(JsonUtil.hasAnySchemaValue("{\"customerId\":\"\"}", schema));
    }
}
