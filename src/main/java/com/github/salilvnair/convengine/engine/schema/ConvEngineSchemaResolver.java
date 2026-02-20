package com.github.salilvnair.convengine.engine.schema;

import java.util.List;
import java.util.Map;

public interface ConvEngineSchemaResolver {

    boolean supports(String schemaDefinition);

    Map<String, Object> schemaFieldDetails(String schemaDefinition);

    List<String> missingRequiredFields(String schemaDefinition, String contextJson);

    boolean isSchemaComplete(String schemaDefinition, String contextJson);

    boolean hasAnySchemaValue(String contextJson, String schemaDefinition);

    String seedContextFromInputParams(String currentContextJson, Map<String, Object> inputParams, String schemaDefinition);

    String sanitizeExtractedJson(String extractedJson);

    String mergeContextJson(String currentContextJson, String extractedJson);

    ConvEngineSchemaComputation compute(
            String schemaDefinition,
            String mergedContextJson,
            Map<String, Object> schemaFieldDetails
    );
}
