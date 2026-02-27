package com.github.salilvnair.convengine.engine.mcp.executor.adapter;

import com.github.salilvnair.convengine.engine.mcp.executor.http.HttpApiRequestSpec;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;

import java.util.Map;

/**
 * Advanced HTTP_API SPI for framework-managed retries, circuit breaking,
 * auth injection, timeout policy and response mapping.
 */
public interface HttpApiRequestingToolHandler extends HttpApiToolHandler {

    HttpApiRequestSpec requestSpec(CeMcpTool tool, Map<String, Object> args, EngineSession session);

    @Override
    default Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        return requestSpec(tool, args, session);
    }
}
