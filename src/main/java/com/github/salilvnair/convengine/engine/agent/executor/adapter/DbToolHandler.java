package com.github.salilvnair.convengine.engine.agent.executor.adapter;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAgentTool;

import java.util.Map;

/**
 * Consumer-facing SPI for tool-code specific DB execution.
 * Implement one handler per MCP DB tool code when SQL-template execution is not enough.
 */
public interface DbToolHandler {
    String toolCode();

    Object execute(CeAgentTool tool, Map<String, Object> args, EngineSession session);
}
