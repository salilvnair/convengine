package com.github.salilvnair.convengine.engine.mcp.executor;

import com.github.salilvnair.convengine.engine.mcp.McpDbExecutor;
import com.github.salilvnair.convengine.engine.mcp.McpToolRegistry;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpDbTool;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class McpDbToolExecutor implements McpToolExecutor {

    private final McpDbExecutor dbExecutor;
    private final McpToolRegistry registry;

    @Override
    public String toolGroup() {
        return "DB";
    }

    @Override
    public String execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        CeMcpDbTool dbTool = registry.requireDbTool(tool.getToolCode());
        return dbExecutor.execute(dbTool, args);
    }
}
