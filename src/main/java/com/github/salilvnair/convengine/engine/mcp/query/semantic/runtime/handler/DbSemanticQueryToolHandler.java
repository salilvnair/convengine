package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.handler;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.core.*;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "convengine.mcp.db.semantic", name = "enabled", havingValue = "true")
public class DbSemanticQueryToolHandler implements DbToolHandler {

    private final ConvEngineMcpConfig mcpConfig;
    private final SemanticQueryRuntimeService runtimeService;

    @Override
    public String toolCode() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        return cfg.getToolCode() == null || cfg.getToolCode().isBlank() ? "db.semantic.query" : cfg.getToolCode();
    }

    @Override
    public Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        String question = extractQuestion(args, session);
        return runtimeService.plan(question, session);
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
