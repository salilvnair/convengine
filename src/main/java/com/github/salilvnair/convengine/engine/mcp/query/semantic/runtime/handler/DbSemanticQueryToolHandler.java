package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.handler;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.core.*;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "convengine.mcp.db.semantic", name = "enabled", havingValue = "true")
public class DbSemanticQueryToolHandler implements DbToolHandler {

    private static final Pattern NON_READ_ONLY_SQL_PATTERN = Pattern.compile(
            "(?is)\\b(" +
                    "delete\\s+from" +
                    "|update\\s+[a-zA-Z_][a-zA-Z0-9_]*\\s+set" +
                    "|drop\\s+table" +
                    "|truncate\\s+table" +
                    "|alter\\s+table" +
                    "|insert\\s+into" +
                    "|create\\s+table" +
                    "|grant\\s+[^\\s]+" +
                    "|revoke\\s+[^\\s]+" +
                    ")\\b"
    );

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
        if (isNonReadOnlyQuestion(question)) {
            return blockedResponse(question);
        }
        return runtimeService.plan(question, session);
    }

    private boolean isNonReadOnlyQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return NON_READ_ONLY_SQL_PATTERN.matcher(question).find();
    }

    private Map<String, Object> blockedResponse(String question) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "semantic");
        out.put("question", question == null ? "" : question);
        out.put("blocked", true);
        out.put("reason", "READ_ONLY_GUARDRAIL");
        out.put("summary", "Blocked by semantic-query read-only guardrail. DML/DDL operations are not allowed.");
        out.put("next", "failed");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("toolCode", toolCode());
        meta.put("stageCode", "runtime-entry");
        meta.put("lifecycle", "BLOCKED");
        out.put("_meta", meta);
        return out;
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
