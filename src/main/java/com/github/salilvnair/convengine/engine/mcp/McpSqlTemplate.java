package com.github.salilvnair.convengine.engine.mcp;

import com.github.salilvnair.convengine.entity.CeMcpDbTool;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class McpSqlTemplate {

    private static final Pattern IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?$");


    private McpSqlTemplate() {}

    public static String expandIdentifiers(CeMcpDbTool dbTool, Map<String, Object> args) {
        String sql = dbTool.getSqlTemplate();

        // Replace ${name} placeholders (identifiers only)
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null) continue;

            String token = "${" + k + "}";
            if (!sql.contains(token)) continue;

            String ident = String.valueOf(v).trim();
            if (!IDENT.matcher(ident).matches()) {
                throw new IllegalArgumentException("Unsafe identifier for " + k + ": " + ident);
            }
            validateIdentifier(dbTool.getToolId(), k, ident, dbTool);
            sql = sql.replace(token, ident);
        }
        return sql;
    }

    private static void validateIdentifier(Long toolId, String name, String ident, CeMcpDbTool dbTool) {
        Map<String, Set<String>> allowedIdentifiers = dbTool.getAllowedIdentifiers();
        if(allowedIdentifiers == null) {
            return; // no restrictions
        }
        Set<String> allowed = allowedIdentifiers.get(name);

        if (allowed == null || !allowed.contains(ident)) {
            throw new IllegalArgumentException(
                    "Identifier not allowed for CeMcpDbTool id " + toolId +
                            ": " + name + "=" + ident
            );
        }
    }
}
