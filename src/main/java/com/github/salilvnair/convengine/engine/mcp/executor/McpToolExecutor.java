package com.github.salilvnair.convengine.engine.mcp.executor;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;

import java.util.Map;

public interface McpToolExecutor {
    String toolGroup();
    String execute(CeMcpTool tool, Map<String, Object> args, EngineSession session);
}
