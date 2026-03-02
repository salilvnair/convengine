package com.github.salilvnair.convengine.engine.mcp.executor.handler;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.mcp.knowledge.DbKnowledgeGraphRuntimeService;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "convengine.mcp.db.knowledge-graph", name = "enabled", havingValue = "true")
public class DbkgPlaybookValidateToolHandler implements DbToolHandler {

    private final DbKnowledgeGraphRuntimeService runtimeService;
    private final ConvEngineMcpConfig mcpConfig;

    @Override
    public String toolCode() {
        return mcpConfig.getDb().getKnowledgeGraph().getPlaybookValidateToolCode();
    }

    @Override
    public Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        return runtimeService.validatePlaybook(args, session);
    }
}
