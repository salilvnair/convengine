package com.github.salilvnair.convengine.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jayway.jsonpath.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonPathUtilTest {

    private final JsonPathUtil jsonPathUtil = new JsonPathUtil();

    @Test
    void searchReturnsMatchedValuesAsList() {
        List<String> values = jsonPathUtil.search(
                "{\"customer\":{\"id\":\"1234\"}}",
                "$.customer.id",
                new TypeRef<>() {});

        assertEquals(List.of("1234"), values);
    }

    @Test
    void setUpdatesJsonValueAndReturnsJsonString() {
        String updated = jsonPathUtil.set(
                "{\"loan\":{\"amount\":35000}}",
                "$.loan.amount",
                350000,
                true);

        assertEquals("{\"loan\":{\"amount\":350000}}", updated);
    }

    @Test
    void putAddsObjectFieldAndConvertsToTypedMap() {
        Map<String, Object> updated = jsonPathUtil.put(
                "{\"loan\":{}}",
                "$.loan",
                "tenureMonths",
                24,
                new TypeReference<>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> loan = (Map<String, Object>) updated.get("loan");
        assertEquals(24, loan.get("tenureMonths"));
    }
}
