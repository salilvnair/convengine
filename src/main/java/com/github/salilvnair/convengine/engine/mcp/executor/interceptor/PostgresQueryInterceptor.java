package com.github.salilvnair.convengine.engine.mcp.executor.interceptor;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;

import java.util.Map;

/**
 * Interceptor SPI for postgres.query SQL normalization/validation.
 *
 * Consumers can provide their own beans with higher @Order to override default behavior.
 */
public interface PostgresQueryInterceptor {

    /**
     * Whether this interceptor should run for this invocation.
     */
    default boolean supports(CeMcpTool tool, EngineSession session, Map<String, Object> args) {
        return true;
    }

    /**
     * Returns SQL to execute (can return original sql unchanged).
     */
    String intercept(String sql, CeMcpTool tool, EngineSession session, Map<String, Object> args);
}
