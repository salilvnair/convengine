package com.github.salilvnair.convengine.engine.mcp.executor.http;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;

import java.util.List;

public record HttpApiExecutionPolicy(
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxAttempts,
        long initialBackoffMs,
        long maxBackoffMs,
        double backoffMultiplier,
        boolean circuitBreakerEnabled,
        int circuitFailureThreshold,
        long circuitOpenMs,
        List<Integer> retryStatusCodes,
        boolean retryOnIOException
) {

    public static HttpApiExecutionPolicy fromDefaults(ConvEngineMcpConfig.HttpApi.Policy defaults) {
        return new HttpApiExecutionPolicy(
                Math.max(defaults.getConnectTimeoutMs(), 100),
                Math.max(defaults.getReadTimeoutMs(), 100),
                Math.max(defaults.getMaxAttempts(), 1),
                Math.max(defaults.getInitialBackoffMs(), 0),
                Math.max(defaults.getMaxBackoffMs(), 0),
                Math.max(defaults.getBackoffMultiplier(), 1.0d),
                defaults.isCircuitBreakerEnabled(),
                Math.max(defaults.getCircuitFailureThreshold(), 1),
                Math.max(defaults.getCircuitOpenMs(), 1000L),
                defaults.getRetryStatusCodes() == null ? List.of() : defaults.getRetryStatusCodes(),
                defaults.isRetryOnIOException());
    }
}
