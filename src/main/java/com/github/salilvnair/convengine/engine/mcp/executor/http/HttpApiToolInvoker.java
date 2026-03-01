package com.github.salilvnair.convengine.engine.mcp.executor.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.api.processor.rest.facade.RestWebServiceFacade;
import com.github.salilvnair.api.processor.rest.handler.RestWebServiceDelegate;
import com.github.salilvnair.api.processor.rest.handler.RestWebServiceHandler;
import com.github.salilvnair.api.processor.rest.model.RestWebServiceRequest;
import com.github.salilvnair.api.processor.rest.model.RestWebServiceResponse;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.HttpApiProcessorToolHandler;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HttpApiToolInvoker {

    private final ConvEngineMcpConfig mcpConfig;
    private final ObjectProvider<RestWebServiceFacade> restWebServiceFacadeProvider;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CircuitState> circuitByTool = new ConcurrentHashMap<>();

    public HttpApiToolInvoker(
            ConvEngineMcpConfig mcpConfig,
            ObjectProvider<RestWebServiceFacade> restWebServiceFacadeProvider
    ) {
        this.mcpConfig = mcpConfig;
        this.restWebServiceFacadeProvider = restWebServiceFacadeProvider;
    }

    public Object invoke(String toolCode, HttpApiRequestSpec spec) {
        return invoke(toolCode, spec, null, Map.of(), null);
    }

    public Object invoke(
            String toolCode,
            HttpApiRequestSpec spec,
            CeMcpTool tool,
            Map<String, Object> args,
            EngineSession session
    ) {
        if (spec == null) {
            throw new IllegalStateException("HTTP_API request spec cannot be null");
        }
        if (spec.url() == null || spec.url().isBlank()) {
            throw new IllegalStateException("HTTP_API request URL cannot be blank");
        }

        HttpApiExecutionPolicy policy = spec.policy() != null
                ? spec.policy()
                : HttpApiExecutionPolicy.fromDefaults(mcpConfig.getHttpApi().getDefaults());

        enforceCircuit(toolCode, policy);

        int attempt = 1;
        long backoffMs = policy.initialBackoffMs();
        Throwable lastError = null;

        while (attempt <= policy.maxAttempts()) {
            long startedAt = System.currentTimeMillis();
            try {
                HttpResponse<String> response = executeOnce(spec, policy);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    onSuccess(toolCode);
                    return mapResponse(response.body(), status, attempt, startedAt, spec.responseMapping());
                }
                lastError = new IllegalStateException("HTTP_API status " + status);
                if (!policy.retryStatusCodes().contains(status) || attempt >= policy.maxAttempts()) {
                    onFailure(toolCode, policy);
                    throw new IllegalStateException("HTTP_API call failed with status " + status + " after " + attempt + " attempt(s)");
                }
            } catch (IOException io) {
                lastError = io;
                if (!policy.retryOnIOException() || attempt >= policy.maxAttempts()) {
                    onFailure(toolCode, policy);
                    throw new IllegalStateException("HTTP_API call failed due to IO error after " + attempt + " attempt(s)", io);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                onFailure(toolCode, policy);
                throw new IllegalStateException("HTTP_API call interrupted", interrupted);
            } catch (RuntimeException runtime) {
                onFailure(toolCode, policy);
                throw runtime;
            }

            sleep(backoffMs);
            backoffMs = (long) Math.min(policy.maxBackoffMs(), Math.max(1L, Math.round(backoffMs * policy.backoffMultiplier())));
            attempt++;
        }

        onFailure(toolCode, policy);
        throw new IllegalStateException("HTTP_API call failed after retries", lastError);
    }

    public Object invokeUsingApiProcessor(
            String toolCode,
            HttpApiProcessorToolHandler toolHandler,
            CeMcpTool tool,
            Map<String, Object> args,
            EngineSession session
    ) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        RestWebServiceHandler wsHandler = toolHandler.wsHandler(tool, safeArgs, session);
        if (wsHandler == null) {
            throw new IllegalStateException("HTTP_API api-processor wsHandler cannot be null for tool_code=" + toolCode);
        }

        Map<String, Object> wsMap = toolHandler.wsMap(tool, safeArgs, session);
        if (wsMap == null) {
            wsMap = new LinkedHashMap<>();
        }

        ApiProcessorInvocationContext context = toolHandler.wsContext(tool, safeArgs, session);
        if (context == null) {
            context = new ApiProcessorInvocationContext(safeArgs);
        }
        Object[] invocationArgs = toolHandler.wsInvocationArgs(tool, safeArgs, session, context);
        if (invocationArgs == null || invocationArgs.length == 0) {
            invocationArgs = new Object[]{context};
        }

        RestWebServiceHandler wrappedHandler = wrapHandler(wsHandler, context);
        RestWebServiceFacade facade = restWebServiceFacadeProvider.getIfAvailable(RestWebServiceFacade::new);
        facade.initiate(wrappedHandler, wsMap, invocationArgs);

        Object result = toolHandler.extractResponse(tool, safeArgs, session, wsMap, context);
        Class<?> mapperClass = toolHandler.responseMapperClass(tool, safeArgs, session);
        if (mapperClass != null && result != null && !mapperClass.isInstance(result)) {
            return mapper.convertValue(result, mapperClass);
        }
        return result == null ? Map.of() : result;
    }

    private HttpResponse<String> executeOnce(HttpApiRequestSpec spec, HttpApiExecutionPolicy policy)
            throws IOException, InterruptedException {

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(policy.connectTimeoutMs()))
                .build();

        String url = appendQuery(spec.url(), spec.queryParams());
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(policy.readTimeoutMs()));

        Map<String, String> headers = new LinkedHashMap<>();
        if (spec.headers() != null) {
            headers.putAll(spec.headers());
        }
        applyAuth(headers, spec.auth());

        headers.forEach(request::header);

        HttpMethod method = resolveHttpMethod(spec.method());
        String body = serializeBody(spec.body());

        if (HttpMethod.GET.equals(method) || HttpMethod.DELETE.equals(method)) {
            request.method(method.name(), HttpRequest.BodyPublishers.noBody());
        } else {
            if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            }
            request.method(method.name(), HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }

        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Object mapResponse(String body, int status, int attempt, long startedAt, HttpApiResponseMapping mapping) {
        HttpApiResponseMapping responseMapping = mapping == null ? HttpApiResponseMapping.rawJson() : mapping;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(HttpApiExecutorConstants.RESPONSE_KEY_STATUS, status);
        out.put(HttpApiExecutorConstants.RESPONSE_KEY_ATTEMPT, attempt);
        out.put(HttpApiExecutorConstants.RESPONSE_KEY_LATENCY_MS, Math.max(0L, System.currentTimeMillis() - startedAt));

        Object mapped;
        HttpApiResponseMappingMode mode =
                responseMapping.mode() == null ? HttpApiResponseMappingMode.RAW_JSON : responseMapping.mode();
        switch (mode) {
            case TEXT -> mapped = body == null ? "" : body;
            case JSON_PATH -> mapped = readJsonPath(body, responseMapping.jsonPath());
            case FIELD_TEMPLATE -> mapped = readFieldTemplate(body, responseMapping.fieldPaths());
            case MAPPER_CLASS -> mapped = readMapperClass(body, responseMapping.mapperClassName());
            default -> mapped = parseJsonOrText(body);
        }
        out.put(HttpApiExecutorConstants.RESPONSE_KEY_MAPPED, mapped);
        return out;
    }

    private Object readJsonPath(String body, String jsonPath) {
        if (jsonPath == null || jsonPath.isBlank()) {
            return parseJsonOrText(body);
        }
        return JsonPath.read(Objects.requireNonNullElse(body, "{}"), jsonPath);
    }

    private Map<String, Object> readFieldTemplate(String body, Map<String, String> fieldPaths) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        if (fieldPaths == null || fieldPaths.isEmpty()) {
            return mapped;
        }
        for (Map.Entry<String, String> entry : fieldPaths.entrySet()) {
            String key = entry.getKey();
            String path = entry.getValue();
            if (key == null || key.isBlank() || path == null || path.isBlank()) {
                continue;
            }
            mapped.put(key, JsonPath.read(Objects.requireNonNullElse(body, "{}"), path));
        }
        return mapped;
    }

    private Object readMapperClass(String body, String mapperClassName) {
        if (mapperClassName == null || mapperClassName.isBlank()) {
            return parseJsonOrText(body);
        }
        Object parsed = parseJsonOrText(body);
        try {
            Class<?> target = Class.forName(mapperClassName);
            return mapper.convertValue(parsed, target);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to map HTTP_API response using mapperClassName=" + mapperClassName, e);
        }
    }

    private Object parseJsonOrText(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readTree(body);
        } catch (Exception ignored) {
            return Map.of(HttpApiExecutorConstants.RESPONSE_KEY_TEXT, body);
        }
    }

    private String serializeBody(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String text) {
            return text;
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize HTTP_API request body", e);
        }
    }

    private String appendQuery(String baseUrl, Map<String, Object> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return baseUrl;
        }
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, Object> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
            query.append('=');
            query.append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
        }
        if (query.isEmpty()) {
            return baseUrl;
        }
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + query;
    }

    private void applyAuth(Map<String, String> headers, HttpApiAuthSpec auth) {
        HttpApiAuthSpec safeAuth = auth == null ? HttpApiAuthSpec.none() : auth;
        HttpApiAuthType type = safeAuth.type() == null ? HttpApiAuthType.NONE : safeAuth.type();

        switch (type) {
            case NONE -> {
            }
            case API_KEY -> {
                String header = safeAuth.headerName() == null || safeAuth.headerName().isBlank()
                        ? HttpApiExecutorConstants.DEFAULT_API_KEY_HEADER
                        : safeAuth.headerName();
                headers.put(header, Objects.requireNonNullElse(safeAuth.value(), ""));
            }
            case BEARER_STATIC -> headers.put(
                    HttpApiExecutorConstants.HEADER_AUTHORIZATION,
                    (safeAuth.prefix() == null || safeAuth.prefix().isBlank() ? HttpApiExecutorConstants.DEFAULT_BEARER_PREFIX : safeAuth.prefix())
                            + " "
                            + Objects.requireNonNullElse(safeAuth.value(), ""));
        }
    }

    private HttpMethod resolveHttpMethod(String method) {
        if (method == null || method.isBlank()) {
            return HttpMethod.GET;
        }
        try {
            return HttpMethod.valueOf(method.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unsupported HTTP_API method: " + method, ex);
        }
    }

    private void enforceCircuit(String toolCode, HttpApiExecutionPolicy policy) {
        if (!policy.circuitBreakerEnabled()) {
            return;
        }
        CircuitState state = circuitByTool.computeIfAbsent(normalizeToolCode(toolCode), t -> new CircuitState());
        if (System.currentTimeMillis() < state.openUntilEpochMs) {
            throw new IllegalStateException("Circuit open for tool " + normalizeToolCode(toolCode));
        }
    }

    private void onSuccess(String toolCode) {
        CircuitState state = circuitByTool.computeIfAbsent(normalizeToolCode(toolCode), t -> new CircuitState());
        state.failureCount = 0;
        state.openUntilEpochMs = 0L;
    }

    private void onFailure(String toolCode, HttpApiExecutionPolicy policy) {
        if (!policy.circuitBreakerEnabled()) {
            return;
        }
        CircuitState state = circuitByTool.computeIfAbsent(normalizeToolCode(toolCode), t -> new CircuitState());
        state.failureCount++;
        if (state.failureCount >= policy.circuitFailureThreshold()) {
            state.openUntilEpochMs = System.currentTimeMillis() + policy.circuitOpenMs();
            state.failureCount = 0;
        }
    }

    private String normalizeToolCode(String toolCode) {
        return toolCode == null || toolCode.isBlank() ? "UNKNOWN" : toolCode.trim().toUpperCase(Locale.ROOT);
    }

    private void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class CircuitState {
        private int failureCount;
        private long openUntilEpochMs;
    }

    private RestWebServiceHandler wrapHandler(
            RestWebServiceHandler delegate,
            ApiProcessorInvocationContext context
    ) {
        return new RestWebServiceHandler() {
            @Override
            public RestWebServiceDelegate delegate() {
                return delegate.delegate();
            }

            @Override
            public RestWebServiceRequest prepareRequest(Map<String, Object> restWsMap, Object... objects) {
                return delegate.prepareRequest(restWsMap, objects);
            }

            @Override
            public void processResponse(
                    RestWebServiceRequest request,
                    RestWebServiceResponse response,
                    Map<String, Object> restWsMap,
                    Object... objects
            ) {
                context.setRawResponse(response);
                delegate.processResponse(request, response, restWsMap, objects);
            }

            @Override
            public String webServiceName() {
                return delegate.webServiceName();
            }

            @Override
            public boolean printLogs() {
                return delegate.printLogs();
            }

            @Override
            public boolean emptyPayLoad() {
                return delegate.emptyPayLoad();
            }
        };
    }
}
