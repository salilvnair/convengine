package com.github.salilvnair.convengine.engine.mcp.executor.http;

import org.springframework.http.HttpMethod;

import java.util.Map;

public record HttpApiRequestSpec(
        String method,
        String url,
        Map<String, String> headers,
        Map<String, Object> queryParams,
        Object body,
        HttpApiAuthSpec auth,
        HttpApiExecutionPolicy policy,
        HttpApiResponseMapping responseMapping
) {
    public static HttpApiRequestSpec of(String method, String url) {
        return new HttpApiRequestSpec(
                method,
                url,
                Map.of(),
                Map.of(),
                null,
                HttpApiAuthSpec.none(),
                null,
                HttpApiResponseMapping.rawJson());
    }

    public static HttpApiRequestSpec of(HttpMethod method, String url) {
        return of(method == null ? null : method.name(), url);
    }
}
