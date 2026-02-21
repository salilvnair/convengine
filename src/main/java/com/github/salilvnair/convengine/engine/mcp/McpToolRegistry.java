package com.github.salilvnair.convengine.engine.mcp;

import com.github.salilvnair.convengine.entity.CeMcpDbTool;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.github.salilvnair.convengine.repo.McpDbToolRepository;
import com.github.salilvnair.convengine.repo.McpToolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@RequiredArgsConstructor
@Component
public class McpToolRegistry {

    private final McpToolRepository toolRepo;
    private final McpDbToolRepository dbToolRepo;

    public List<CeMcpTool> listEnabledTools(String intentCode, String stateCode) {
        return toolRepo.findEnabledByIntentAndState(intentCode, stateCode);
    }

    public CeMcpTool requireTool(String toolCode, String intentCode, String stateCode) {
        return toolRepo.findByToolCodeEnabledAndIntentAndState(toolCode, intentCode, stateCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing enabled MCP tool for current intent/state: " + toolCode
                ));
    }

    public CeMcpDbTool requireDbTool(String toolCode) {
        return dbToolRepo.findByTool_ToolCodeAndTool_EnabledTrue(toolCode)
                .orElseThrow(() -> new IllegalStateException("Missing enabled DB tool: " + toolCode));
    }

    public String normalizeToolGroup(String toolGroup) {
        if (toolGroup == null || toolGroup.isBlank()) {
            return "DB";
        }
        String normalized = toolGroup.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DB", "MCP_DB", "DATABASE", "SQL" -> "DB";
            case "HTTP", "HTTP_API", "API" -> "HTTP_API";
            case "ACTION", "WORKFLOW_ACTION", "WORKFLOW" -> "WORKFLOW_ACTION";
            case "DOC", "DOCUMENT", "DOCUMENT_RETRIEVAL", "RAG" -> "DOCUMENT_RETRIEVAL";
            case "CALC", "CALCULATOR", "TRANSFORM", "CALCULATOR_TRANSFORM" -> "CALCULATOR_TRANSFORM";
            case "NOTIFY", "NOTIFICATION" -> "NOTIFICATION";
            case "FILE", "FILES" -> "FILES";
            default -> normalized;
        };
    }
}
