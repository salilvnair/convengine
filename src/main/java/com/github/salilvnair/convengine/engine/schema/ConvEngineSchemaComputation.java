package com.github.salilvnair.convengine.engine.schema;

import java.util.List;
import java.util.Map;

public record ConvEngineSchemaComputation(
        boolean schemaComplete,
        boolean hasAnySchemaValue,
        List<String> missingFields,
        Map<String, Object> missingFieldOptions
) {
}
