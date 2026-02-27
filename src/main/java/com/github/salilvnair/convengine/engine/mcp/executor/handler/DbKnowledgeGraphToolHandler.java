package com.github.salilvnair.convengine.engine.mcp.executor.handler;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.mcp.knowledge.DbKnowledgeGraphService;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "convengine.mcp.db.knowledge", name = "enabled", havingValue = "true")
public class DbKnowledgeGraphToolHandler implements DbToolHandler {

    private final DbKnowledgeGraphService knowledgeGraphService;
    private final ConvEngineMcpConfig mcpConfig;

    @Override
    public String toolCode() {
        return mcpConfig.getDb().getKnowledge().getToolCode();
    }

    @Override
    public Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        String question = extractQuestion(args, session);
        return knowledgeGraphService.resolveKnowledge(question);
    }

    private String extractQuestion(Map<String, Object> args, EngineSession session) {
        if (args != null) {
            Object query = args.get("question");
            if (query == null) {
                query = args.get("query");
            }
            if (query == null) {
                query = args.get("user_input");
            }
            if (query != null && !String.valueOf(query).isBlank()) {
                return String.valueOf(query);
            }
        }
        return session == null ? "" : session.getUserText();
    }
}
