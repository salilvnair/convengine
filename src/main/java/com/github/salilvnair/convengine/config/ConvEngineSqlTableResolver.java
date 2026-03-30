package com.github.salilvnair.convengine.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ConvEngineSqlTableResolver {

    private final ConvEngineEntityConfig entityConfig;

    public String resolveTableName(String logicalTableName) {
        if (logicalTableName == null || logicalTableName.isBlank()) {
            return logicalTableName;
        }
        String normalized = logicalTableName.trim();
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("CE_")) {
            return normalized;
        }
        String key = upper.substring(3);
        Map<String, String> tables = entityConfig == null ? null : entityConfig.getTables();
        if (tables == null || tables.isEmpty()) {
            return normalized;
        }
        String mapped = tables.get(key);
        return (mapped == null || mapped.isBlank()) ? normalized : mapped.trim();
    }

    public String resolveSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        Map<String, String> tables = entityConfig == null ? null : entityConfig.getTables();
        if (tables == null || tables.isEmpty()) {
            return sql;
        }
        String out = sql;
        for (Map.Entry<String, String> entry : tables.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            String logical = "ce_" + entry.getKey().trim().toLowerCase(Locale.ROOT);
            String replacement = entry.getValue().trim();
            out = Pattern.compile("\\b" + Pattern.quote(logical) + "\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(out)
                    .replaceAll(replacement);
        }
        return out;
    }
}
