package com.github.salilvnair.convengine.engine.mcp;

import com.github.salilvnair.convengine.entity.CeMcpDbTool;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.github.salilvnair.convengine.repo.McpDbToolRepository;
import com.github.salilvnair.convengine.repo.McpToolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class McpToolRegistry {

    private final McpToolRepository toolRepo;
    private final McpDbToolRepository dbToolRepo;

    public List<CeMcpTool> listEnabledTools() {
        return toolRepo.findByEnabledTrueOrderByToolGroupAscToolCodeAsc();
    }

    public CeMcpDbTool requireDbTool(String toolCode) {
        return dbToolRepo.findByTool_ToolCodeAndTool_EnabledTrue(toolCode)
                .orElseThrow(() -> new IllegalStateException("Missing enabled DB tool: " + toolCode));
    }
}
