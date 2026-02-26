package com.github.salilvnair.convengine.engine.mcp.executor.http;

import org.springframework.http.HttpHeaders;

public final class HttpApiExecutorConstants {

    private HttpApiExecutorConstants() {
    }

    public static final String DEFAULT_API_KEY_HEADER = "X-API-KEY";
    public static final String DEFAULT_BEARER_PREFIX = "Bearer";

    public static final String RESPONSE_KEY_STATUS = "status";
    public static final String RESPONSE_KEY_ATTEMPT = "attempt";
    public static final String RESPONSE_KEY_LATENCY_MS = "latencyMs";
    public static final String RESPONSE_KEY_MAPPED = "mapped";
    public static final String RESPONSE_KEY_TEXT = "text";

    public static final String HEADER_AUTHORIZATION = HttpHeaders.AUTHORIZATION;
}
