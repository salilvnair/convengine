package com.github.salilvnair.convengine.engine.agent;

import com.github.salilvnair.convengine.entity.CeAgentDbTool;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class AgentSqlTemplate {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?$");

    private AgentSqlTemplate() {
    }

    public static String expandIdentifiers(CeAgentDbTool dbTool, Map<String, Object> args) {
        String sql = dbTool.getSqlTemplate();

        // Replace ${name} placeholders (identifiers only)
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null)
                continue;

            String token = "${" + k + "}";
            if (!sql.contains(token))
                continue;

            String identifier = String.valueOf(v).trim();
            if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
                throw new IllegalArgumentException("Unsafe identifier for " + k + ": " + identifier);
            }
            validateIdentifier(dbTool.getToolId(), k, identifier, dbTool);
            sql = sql.replace(token, identifier);
        }
        return sql;
    }

    private static void validateIdentifier(Long toolId, String name, String ident, CeAgentDbTool dbTool) {
        Map<String, Set<String>> allowedIdentifiers = dbTool.getAllowedIdentifiers();
        if (allowedIdentifiers == null) {
            return; // no restrictions
        }
        Set<String> allowed = allowedIdentifiers.get(name);

        if (allowed == null || !allowed.contains(ident)) {
            throw new IllegalArgumentException(
                    "Identifier not allowed for CeAgentDbTool id " + toolId +
                            ": " + name + "=" + ident);
        }
    }
}
