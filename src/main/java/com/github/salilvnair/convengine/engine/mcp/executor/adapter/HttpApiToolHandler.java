package com.github.salilvnair.convengine.engine.mcp.executor.adapter;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;

import java.util.Map;

/**
 * Consumer-facing SPI for tool-code specific HTTP/API execution.
 * Implement one handler per MCP tool code (for example: "crm.lookup", "order.status").
 */
public interface HttpApiToolHandler {
    String toolCode();

    Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session);
}
