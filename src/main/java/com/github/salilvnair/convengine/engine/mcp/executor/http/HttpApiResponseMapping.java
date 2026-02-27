package com.github.salilvnair.convengine.engine.mcp.executor.http;

import java.util.Map;

public record HttpApiResponseMapping(
        HttpApiResponseMappingMode mode,
        String jsonPath,
        Map<String, String> fieldPaths,
        String mapperClassName
) {
    public HttpApiResponseMapping(
            HttpApiResponseMappingMode mode,
            String jsonPath,
            Map<String, String> fieldPaths
    ) {
        this(mode, jsonPath, fieldPaths, null);
    }

    public static HttpApiResponseMapping rawJson() {
        return new HttpApiResponseMapping(HttpApiResponseMappingMode.RAW_JSON, null, Map.of(), null);
    }

    public static HttpApiResponseMapping mapperClass(String mapperClassName) {
        return new HttpApiResponseMapping(HttpApiResponseMappingMode.MAPPER_CLASS, null, Map.of(), mapperClassName);
    }
}
