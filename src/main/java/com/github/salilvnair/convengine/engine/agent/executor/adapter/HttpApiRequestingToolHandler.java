package com.github.salilvnair.convengine.engine.agent.executor.adapter;

import com.github.salilvnair.convengine.engine.agent.executor.http.HttpApiRequestSpec;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAgentTool;

import java.util.Map;

/**
 * Advanced HTTP_API SPI for framework-managed retries, circuit breaking,
 * auth injection, timeout policy and response mapping.
 */
public interface HttpApiRequestingToolHandler extends HttpApiToolHandler {

    HttpApiRequestSpec requestSpec(CeAgentTool tool, Map<String, Object> args, EngineSession session);

    @Override
    default Object execute(CeAgentTool tool, Map<String, Object> args, EngineSession session) {
        return requestSpec(tool, args, session);
    }
}
