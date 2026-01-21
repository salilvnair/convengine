package com.github.salilvnair.convengine.engine.mcp;

import java.util.Map;
import java.util.regex.Pattern;

public final class McpSqlTemplate {

    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private McpSqlTemplate() {}

    public static String expandIdentifiers(String sqlTemplate, Map<String, Object> args) {
        String sql = sqlTemplate;

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
            sql = sql.replace(token, ident);
        }
        return sql;
    }
}
