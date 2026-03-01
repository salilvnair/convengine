package com.github.salilvnair.convengine.engine.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConvEngineSchemaResolverFactoryTest {

    @Test
    void getReturnsFirstSupportingResolver() {
        StubSchemaResolver resolver = new StubSchemaResolver();
        ConvEngineSchemaResolverFactory factory = new ConvEngineSchemaResolverFactory(List.of(resolver));

        ConvEngineSchemaResolver selected = factory.get("{\"type\":\"object\"}");

        assertSame(resolver, selected);
    }

    @Test
    void getThrowsWhenNoResolverSupportsSchema() {
        ConvEngineSchemaResolverFactory factory = new ConvEngineSchemaResolverFactory(List.of(new StubSchemaResolver()));

        assertThrows(IllegalStateException.class, () -> factory.get("yaml:schema"));
    }

    private static final class StubSchemaResolver implements ConvEngineSchemaResolver {
        @Override
        public boolean supports(String schemaDefinition) {
            return schemaDefinition != null && schemaDefinition.startsWith("{");
        }

        @Override
        public Map<String, Object> schemaFieldDetails(String schemaDefinition) {
            return Map.of();
        }

        @Override
        public java.util.List<String> missingRequiredFields(String schemaDefinition, String contextJson) {
            return java.util.List.of();
        }

        @Override
        public boolean isSchemaComplete(String schemaDefinition, String contextJson) {
            return false;
        }

        @Override
        public boolean hasAnySchemaValue(String contextJson, String schemaDefinition) {
            return false;
        }

        @Override
        public String seedContextFromInputParams(String currentContextJson, Map<String, Object> inputParams, String schemaDefinition) {
            return currentContextJson;
        }

        @Override
        public String sanitizeExtractedJson(String extractedJson) {
            return extractedJson;
        }

        @Override
        public String mergeContextJson(String currentContextJson, String extractedJson) {
            return currentContextJson;
        }

        @Override
        public ConvEngineSchemaComputation compute(String schemaDefinition, String mergedContextJson, Map<String, Object> schemaFieldDetails) {
            return null;
        }
    }
}
