package com.github.salilvnair.convengine.engine.helper;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputParamsHelperTest {

    @Test
    void deepCopyReturnsIndependentNestedMap() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("customerId", "1234");
        source.put("details", new LinkedHashMap<>(Map.of("amount", 350000, "tags", List.of("loan", "priority"))));

        Map<String, Object> copied = InputParamsHelper.deepCopy(source);
        @SuppressWarnings("unchecked")
        Map<String, Object> copiedDetails = (Map<String, Object>) copied.get("details");
        copiedDetails.put("amount", 1000);

        @SuppressWarnings("unchecked")
        Map<String, Object> originalDetails = (Map<String, Object>) source.get("details");

        assertNotSame(source, copied);
        assertEquals(350000, originalDetails.get("amount"));
        assertEquals(1000, copiedDetails.get("amount"));
    }

    @Test
    void deepCopyHandlesNullSource() {
        Map<String, Object> copied = InputParamsHelper.deepCopy(null);

        assertTrue(copied.isEmpty());
    }
}
