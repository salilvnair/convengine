package com.github.salilvnair.convengine.engine.mcp.knowledge;

import java.util.List;
import java.util.Map;

public record DbkgPhysicalSchemaCatalog(
        List<Map<String, Object>> objects,
        List<Map<String, Object>> columns,
        List<Map<String, Object>> joinPaths) {
}
