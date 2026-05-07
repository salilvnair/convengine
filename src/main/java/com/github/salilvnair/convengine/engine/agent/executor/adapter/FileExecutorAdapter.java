package com.github.salilvnair.convengine.engine.agent.executor.adapter;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAgentTool;

import java.util.Map;

public interface FileExecutorAdapter {
    String execute(CeAgentTool tool, Map<String, Object> args, EngineSession session);
}

