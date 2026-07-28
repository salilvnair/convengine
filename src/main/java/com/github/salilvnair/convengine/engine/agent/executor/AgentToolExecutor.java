package com.github.salilvnair.convengine.engine.agent.executor;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAgentTool;

import java.util.Map;

public interface AgentToolExecutor {
    String toolGroup();
    String execute(CeAgentTool tool, Map<String, Object> args, EngineSession session);
}
