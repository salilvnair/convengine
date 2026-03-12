package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.handler;

import com.github.salilvnair.convengine.engine.mcp.executor.adapter.DbToolHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticInterpretRequest;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service.SemanticInterpretService;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "convengine.mcp.db.semantic", name = "enabled", havingValue = "true")
public class DbSemanticInterpretToolHandler implements DbToolHandler {

    private final SemanticInterpretService interpretService;

    @Override
    public String toolCode() {
        return "db.semantic.interpret";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        String question = firstNonBlank(
                asText(safeArgs.get("question")),
                asText(safeArgs.get("standalone_query")),
                asText(safeArgs.get("standaloneQuery")),
                asText(safeArgs.get("query")),
                asText(safeArgs.get("user_input")),
                session == null ? null : session.getStandaloneQuery(),
                session == null ? null : session.getResolvedUserInput(),
                session == null ? null : session.getUserText(),
                ""
        );

        Map<String, Object> context = asMap(safeArgs.get("context"));
        Map<String, Object> hints = asMap(safeArgs.get("hints"));
        if (context.isEmpty() && session != null) {
            context = session.contextDict();
        }

        String conversationId = asText(safeArgs.get("conversationId"));
        if ((conversationId == null || conversationId.isBlank()) && session != null && session.getConversationId() != null) {
            conversationId = session.getConversationId().toString();
        }

        SemanticInterpretRequest request = new SemanticInterpretRequest(question, conversationId, context, hints);
        return interpretService.interpret(request, session);
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
