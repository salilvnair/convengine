package com.github.salilvnair.convengine.engine.mcp.preflight;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class DbSqlPreflightService {

    private static final Pattern FROM_PATTERN = Pattern.compile("(?i)\\bfrom\\s+([a-zA-Z0-9_.]+)(?:\\s+([a-zA-Z0-9_]+))?");
    private static final Pattern JOIN_PATTERN = Pattern.compile("(?i)\\bjoin\\s+([a-zA-Z0-9_.]+)(?:\\s+([a-zA-Z0-9_]+))?");
    private static final Pattern COMMA_TABLE_PATTERN = Pattern.compile("(?i),\\s*([a-zA-Z0-9_.]+)(?:\\s+([a-zA-Z0-9_]+))?");
    private static final Pattern ALIAS_COLUMN_PATTERN = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\b");
    private static final Pattern PARAM_EQUALITY_PATTERN = Pattern.compile(
            "(?i)\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*:([a-zA-Z_][a-zA-Z0-9_]*)\\b");
    private static final Pattern LITERAL_EQUALITY_PATTERN = Pattern.compile(
            "(?i)\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*'([^']+)'");

    private final JdbcTemplate jdbcTemplate;
    private final ConvEngineMcpConfig mcpConfig;

    public PreflightResult prepare(String sql, Map<String, Object> params) {
        ConvEngineMcpConfig.Db.Preflight cfg = mcpConfig.getDb().getPreflight();
        if (cfg == null || !cfg.isEnabled() || sql == null || sql.isBlank()) {
            return new PreflightResult(sql, params == null ? Map.of() : new LinkedHashMap<>(params));
        }

        Map<String, Object> mutableParams = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        String workingSql = sql;

        Map<String, String> aliasToTable = extractAliasToTable(workingSql);
        List<String> orderedTables = extractOrderedTables(workingSql);

        if (cfg.getSemantic() != null && cfg.getSemantic().isEnabled()) {
            validateSemanticMappings(aliasToTable, workingSql, cfg.getSemantic());
            validateSemanticJoinPath(orderedTables, cfg.getSemantic());
        }

        Map<String, Map<String, Integer>> tableColumns = loadTableColumns(aliasToTable.values(), cfg.isStrictSchema());

        if (cfg.isStrictSchema()) {
            validateReferencedColumns(aliasToTable, tableColumns, workingSql);
        }
        if (cfg.isCoerceNumeric()) {
            coerceNumericParams(aliasToTable, tableColumns, mutableParams, workingSql, cfg);
            workingSql = coerceNumericLiterals(aliasToTable, tableColumns, workingSql, cfg);
        }

        return new PreflightResult(workingSql, mutableParams);
    }

    public RepairContext buildRepairContext(String sql) {
        Map<String, String> aliasToTable = extractAliasToTable(sql == null ? "" : sql);
        List<String> orderedTables = extractOrderedTables(sql == null ? "" : sql);
        return new RepairContext(
                loadRuntimeSchemaDetails(orderedTables),
                loadRuntimeSemanticHints(aliasToTable, orderedTables));
    }

    private Map<String, Object> loadRuntimeSchemaDetails(List<String> orderedTables) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (orderedTables == null || orderedTables.isEmpty()) {
            return schema;
        }
        jdbcTemplate.execute((Connection conn) -> {
            DatabaseMetaData meta = conn.getMetaData();
            for (String table : orderedTables) {
                String normalized = normalizeTableName(table);
                if (normalized == null) {
                    continue;
                }
                String rawTable = stripSchema(normalized);
                Map<String, Object> oneTable = new LinkedHashMap<>();
                oneTable.put("table", normalized);
                oneTable.put("columns", readColumns(meta, rawTable));
                oneTable.put("primaryKeys", readPrimaryKeys(meta, rawTable));
                oneTable.put("foreignKeys", readForeignKeys(meta, rawTable));
                schema.put(normalized, oneTable);
            }
            return null;
        });
        return schema;
    }

    private List<Map<String, Object>> readColumns(DatabaseMetaData meta, String table) throws java.sql.SQLException {
        List<Map<String, Object>> cols = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(null, null, table, null)) {
            while (rs.next()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", rs.getString("COLUMN_NAME"));
                c.put("dataType", rs.getInt("DATA_TYPE"));
                c.put("typeName", rs.getString("TYPE_NAME"));
                c.put("nullable", rs.getInt("NULLABLE") != ResultSetMetaData.columnNoNulls);
                cols.add(c);
            }
        }
        return cols;
    }

    private List<String> readPrimaryKeys(DatabaseMetaData meta, String table) throws java.sql.SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, null, table)) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                if (col != null && !col.isBlank()) {
                    out.add(col);
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> readForeignKeys(DatabaseMetaData meta, String table) throws java.sql.SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResultSet rs = meta.getImportedKeys(null, null, table)) {
            while (rs.next()) {
                Map<String, Object> fk = new LinkedHashMap<>();
                fk.put("fkColumn", rs.getString("FKCOLUMN_NAME"));
                fk.put("pkTable", rs.getString("PKTABLE_NAME"));
                fk.put("pkColumn", rs.getString("PKCOLUMN_NAME"));
                out.add(fk);
            }
        }
        return out;
    }

    private Map<String, Object> loadRuntimeSemanticHints(Map<String, String> aliasToTable, List<String> orderedTables) {
        ConvEngineMcpConfig.Db.Preflight cfg = mcpConfig.getDb().getPreflight();
        ConvEngineMcpConfig.Db.Preflight.Semantic semanticCfg = cfg == null ? null : cfg.getSemantic();
        Map<String, Object> hints = new LinkedHashMap<>();
        if (semanticCfg == null || !semanticCfg.isEnabled()) {
            return hints;
        }
        hints.put("mappingRows", querySemanticMappingsForTables(semanticCfg, orderedTables));
        hints.put("joinHintRows", querySemanticJoinHintsForTables(semanticCfg, orderedTables));
        hints.put("sourceTableRows", querySemanticSourceTables(semanticCfg, orderedTables));
        hints.put("sourceColumnRows", querySemanticSourceColumns(semanticCfg, orderedTables));
        hints.put("aliases", aliasToTable);
        return hints;
    }

    private List<Map<String, Object>> querySemanticMappingsForTables(
            ConvEngineMcpConfig.Db.Preflight.Semantic semanticCfg,
            List<String> orderedTables) {
        String tableName = requireSafeIdentifier(semanticCfg.getMappingTable());
        String mappedTableCol = requireSafeIdentifier(semanticCfg.getMappingTableColumn());
        String mappedColumnCol = requireSafeIdentifier(semanticCfg.getMappingColumnColumn());
        String sql = "SELECT " + mappedTableCol + ", " + mappedColumnCol + " FROM " + tableName;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            if (orderedTables == null || orderedTables.isEmpty()) {
                return rows;
            }
            Set<String> allow = new LinkedHashSet<>();
            for (String t : orderedTables) {
                if (t != null) {
                    allow.add(normalizeTableName(t));
                }
            }
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object mappedTable = row.get(mappedTableCol);
                if (mappedTable == null) {
                    continue;
                }
                String t = normalizeTableName(String.valueOf(mappedTable));
                if (allow.contains(t)) {
                    filtered.add(row);
                }
            }
            return filtered;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> querySemanticJoinHintsForTables(
            ConvEngineMcpConfig.Db.Preflight.Semantic semanticCfg,
            List<String> orderedTables) {
        String tableName = requireSafeIdentifier(semanticCfg.getJoinHintTable());
        String leftCol = requireSafeIdentifier(semanticCfg.getJoinLeftTableColumn());
        String rightCol = requireSafeIdentifier(semanticCfg.getJoinRightTableColumn());
        String sql = "SELECT " + leftCol + ", " + rightCol + " FROM " + tableName;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            if (orderedTables == null || orderedTables.isEmpty()) {
                return rows;
            }
            Set<String> allow = new LinkedHashSet<>();
            for (String t : orderedTables) {
                if (t != null) {
                    allow.add(normalizeTableName(t));
                }
            }
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object left = row.get(leftCol);
                Object right = row.get(rightCol);
                if (left == null || right == null) {
                    continue;
                }
                String l = normalizeTableName(String.valueOf(left));
                String r = normalizeTableName(String.valueOf(right));
                if (allow.contains(l) || allow.contains(r)) {
                    filtered.add(row);
                }
            }
            return filtered;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> querySemanticSourceTables(
            ConvEngineMcpConfig.Db.Preflight.Semantic semanticCfg,
            List<String> orderedTables) {
        String catalogTable = requireSafeIdentifier(semanticCfg.getSourceTableCatalogTable());
        String tableNameCol = requireSafeIdentifier(semanticCfg.getSourceTableNameColumn());
        String sql = "SELECT * FROM " + catalogTable;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            if (orderedTables == null || orderedTables.isEmpty()) {
                return rows;
            }
            Set<String> allow = normalizeAllowTables(orderedTables);
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String rowTable = readRowValueCaseInsensitive(row, tableNameCol);
                if (rowTable == null) {
                    continue;
                }
                if (allow.contains(normalizeTableName(rowTable))) {
                    filtered.add(row);
                }
            }
            return filtered;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> querySemanticSourceColumns(
            ConvEngineMcpConfig.Db.Preflight.Semantic semanticCfg,
            List<String> orderedTables) {
        String catalogTable = requireSafeIdentifier(semanticCfg.getSourceColumnCatalogTable());
        String tableNameCol = requireSafeIdentifier(semanticCfg.getSourceColumnTableNameColumn());
        String sql = "SELECT * FROM " + catalogTable;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            if (orderedTables == null || orderedTables.isEmpty()) {
                return rows;
            }
            Set<String> allow = normalizeAllowTables(orderedTables);
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String rowTable = readRowValueCaseInsensitive(row, tableNameCol);
                if (rowTable == null) {
                    continue;
                }
                if (allow.contains(normalizeTableName(rowTable))) {
                    filtered.add(row);
                }
            }
            return filtered;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> extractOrderedTables(String sql) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        appendTables(FROM_PATTERN.matcher(sql), ordered);
        appendTables(JOIN_PATTERN.matcher(sql), ordered);
        appendTables(COMMA_TABLE_PATTERN.matcher(sql), ordered);
        return new ArrayList<>(ordered);
    }

    private Map<String, String> extractAliasToTable(String sql) {
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        collectAliases(FROM_PATTERN.matcher(sql), aliasToTable);
        collectAliases(JOIN_PATTERN.matcher(sql), aliasToTable);
        collectAliases(COMMA_TABLE_PATTERN.matcher(sql), aliasToTable);
        return aliasToTable;
    }

    private void collectAliases(Matcher matcher, Map<String, String> aliasToTable) {
        while (matcher.find()) {
            String table = normalizeTableName(matcher.group(1));
            String alias = matcher.group(2);
            if (table == null || table.isBlank()) {
                continue;
            }
            aliasToTable.put(table, table);
            if (alias != null && !alias.isBlank()) {
                aliasToTable.put(alias.toLowerCase(Locale.ROOT), table);
            }
        }
    }

    private void appendTables(Matcher matcher, Set<String> orderedTables) {
        while (matcher.find()) {
            String table = normalizeTableName(matcher.group(1));
            if (table != null && !table.isBlank()) {
                orderedTables.add(table);
            }
        }
    }

    private void validateSemanticMappings(
            Map<String, String> aliasToTable,
            String sql,
            ConvEngineMcpConfig.Db.Preflight.Semantic semanticCfg) {
        Set<String> semanticColumns = loadSemanticColumns(semanticCfg);
        if (semanticColumns.isEmpty()) {
            if (semanticCfg.isStrictMapping()) {
                throw new IllegalArgumentException(
                        "SQL preflight failed: semantic mapping table is empty or not readable: " + semanticCfg.getMappingTable());
            }
            return;
        }
        Matcher matcher = ALIAS_COLUMN_PATTERN.matcher(sql);
        List<String> unknown = new ArrayList<>();
        while (matcher.find()) {
            String alias = matcher.group(1).toLowerCase(Locale.ROOT);
            String column = matcher.group(2).toLowerCase(Locale.ROOT);
            String table = aliasToTable.get(alias);
            if (table == null) {
                continue;
            }
            String key = table + "." + column;
            if (!semanticColumns.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty() && semanticCfg.isStrictMapping()) {
            throw new IllegalArgumentException("SQL preflight failed: unmapped semantic columns " + unknown);
        }
    }

    private Set<String> loadSemanticColumns(ConvEngineMcpConfig.Db.Preflight.Semantic semanticCfg) {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(loadSemanticColumnsFromMapping(semanticCfg));
        out.addAll(loadSemanticColumnsFromSourceCatalog(semanticCfg));
        return out;
    }

    private Set<String> loadSemanticColumnsFromMapping(ConvEngineMcpConfig.Db.Preflight.Semantic semanticCfg) {
        String tableName = requireSafeIdentifier(semanticCfg.getMappingTable());
        String mappedTableCol = requireSafeIdentifier(semanticCfg.getMappingTableColumn());
        String mappedColumnCol = requireSafeIdentifier(semanticCfg.getMappingColumnColumn());
        String sql = "SELECT " + mappedTableCol + ", " + mappedColumnCol + " FROM " + tableName;
        try {
            return jdbcTemplate.query(sql, rs -> {
                Set<String> out = new LinkedHashSet<>();
                while (rs.next()) {
                    String table = normalizeTableName(rs.getString(1));
                    String col = rs.getString(2);
                    if (table == null || col == null || col.isBlank()) {
                        continue;
                    }
                    out.add(table + "." + col.toLowerCase(Locale.ROOT));
                }
                return out;
            });
        } catch (Exception e) {
            return Set.of();
        }
    }

    private Set<String> loadSemanticColumnsFromSourceCatalog(ConvEngineMcpConfig.Db.Preflight.Semantic semanticCfg) {
        String tableName = requireSafeIdentifier(semanticCfg.getSourceColumnCatalogTable());
        String tableCol = requireSafeIdentifier(semanticCfg.getSourceColumnTableNameColumn());
        String columnCol = requireSafeIdentifier(semanticCfg.getSourceColumnNameColumn());
        String sql = "SELECT " + tableCol + ", " + columnCol + " FROM " + tableName;
        try {
            return jdbcTemplate.query(sql, rs -> {
                Set<String> out = new LinkedHashSet<>();
                while (rs.next()) {
                    String table = normalizeTableName(rs.getString(1));
                    String col = rs.getString(2);
                    if (table == null || col == null || col.isBlank()) {
                        continue;
                    }
                    out.add(table + "." + col.toLowerCase(Locale.ROOT));
                }
                return out;
            });
        } catch (Exception e) {
            return Set.of();
        }
    }

    private void validateSemanticJoinPath(
            List<String> orderedTables,
            ConvEngineMcpConfig.Db.Preflight.Semantic semanticCfg) {
        if (orderedTables.size() < 2) {
            return;
        }
        Set<String> allowedPairs = loadSemanticJoinPairs(semanticCfg);
        if (allowedPairs.isEmpty()) {
            if (semanticCfg.isStrictJoinPath()) {
                throw new IllegalArgumentException(
                        "SQL preflight failed: semantic join hints empty or not readable: " + semanticCfg.getJoinHintTable());
            }
            return;
        }
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < orderedTables.size() - 1; i++) {
            String left = orderedTables.get(i);
            String right = orderedTables.get(i + 1);
            String key = joinKey(left, right);
            if (!allowedPairs.contains(key)) {
                missing.add(left + " -> " + right);
            }
        }
        if (!missing.isEmpty() && semanticCfg.isStrictJoinPath()) {
            throw new IllegalArgumentException("SQL preflight failed: join path not allowed by semantic hints " + missing);
        }
    }

    private Set<String> loadSemanticJoinPairs(ConvEngineMcpConfig.Db.Preflight.Semantic semanticCfg) {
        String tableName = requireSafeIdentifier(semanticCfg.getJoinHintTable());
        String leftCol = requireSafeIdentifier(semanticCfg.getJoinLeftTableColumn());
        String rightCol = requireSafeIdentifier(semanticCfg.getJoinRightTableColumn());
        String sql = "SELECT " + leftCol + ", " + rightCol + " FROM " + tableName;
        try {
            return jdbcTemplate.query(sql, rs -> {
                Set<String> out = new LinkedHashSet<>();
                while (rs.next()) {
                    String left = normalizeTableName(rs.getString(1));
                    String right = normalizeTableName(rs.getString(2));
                    if (left == null || right == null) {
                        continue;
                    }
                    out.add(joinKey(left, right));
                }
                return out;
            });
        } catch (Exception e) {
            return Set.of();
        }
    }

    private String joinKey(String a, String b) {
        String left = normalizeTableName(a);
        String right = normalizeTableName(b);
        if (left == null || right == null) {
            return "";
        }
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }

    private Map<String, Map<String, Integer>> loadTableColumns(Iterable<String> tables, boolean strictSchema) {
        Set<String> uniqueTables = new LinkedHashSet<>();
        for (String table : tables) {
            if (table != null && !table.isBlank()) {
                uniqueTables.add(table.toLowerCase(Locale.ROOT));
            }
        }
        if (uniqueTables.isEmpty()) {
            return Map.of();
        }

        Map<String, Map<String, Integer>> out = new HashMap<>();
        jdbcTemplate.execute((Connection conn) -> {
            DatabaseMetaData meta = conn.getMetaData();
            for (String table : uniqueTables) {
                Map<String, Integer> cols = new HashMap<>();
                String rawTable = stripSchema(table);
                try (ResultSet rs = meta.getColumns(null, null, rawTable, null)) {
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME");
                        int dataType = rs.getInt("DATA_TYPE");
                        if (col != null) {
                            cols.put(col.toLowerCase(Locale.ROOT), dataType);
                        }
                    }
                }
                if (cols.isEmpty() && strictSchema) {
                    throw new IllegalArgumentException("SQL preflight failed: table not found in metadata: " + table);
                }
                out.put(table, cols);
            }
            return null;
        });
        return out;
    }

    private void validateReferencedColumns(
            Map<String, String> aliasToTable,
            Map<String, Map<String, Integer>> tableColumns,
            String sql) {
        Matcher matcher = ALIAS_COLUMN_PATTERN.matcher(sql);
        List<String> unknown = new ArrayList<>();
        while (matcher.find()) {
            String alias = matcher.group(1).toLowerCase(Locale.ROOT);
            String col = matcher.group(2).toLowerCase(Locale.ROOT);
            String table = aliasToTable.get(alias);
            if (table == null) {
                continue;
            }
            Map<String, Integer> cols = tableColumns.getOrDefault(table, Map.of());
            if (!cols.containsKey(col)) {
                unknown.add(table + "." + col);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("SQL preflight failed: unknown columns " + unknown);
        }
    }

    private void coerceNumericParams(
            Map<String, String> aliasToTable,
            Map<String, Map<String, Integer>> tableColumns,
            Map<String, Object> params,
            String sql,
            ConvEngineMcpConfig.Db.Preflight cfg) {
        Matcher matcher = PARAM_EQUALITY_PATTERN.matcher(sql);
        while (matcher.find()) {
            String alias = matcher.group(1).toLowerCase(Locale.ROOT);
            String column = matcher.group(2).toLowerCase(Locale.ROOT);
            String paramName = matcher.group(3);
            String table = aliasToTable.get(alias);
            if (table == null || !params.containsKey(paramName)) {
                continue;
            }
            Integer dataType = tableColumns.getOrDefault(table, Map.of()).get(column);
            if (!isNumericType(dataType)) {
                continue;
            }

            Object raw = params.get(paramName);
            if (raw == null) {
                continue;
            }
            String text = String.valueOf(raw).trim();
            if (isNumericText(text)) {
                continue;
            }
            String mapped = lookupMapping(cfg, table, column, text);
            if (mapped == null) {
                throw new IllegalArgumentException(
                        "SQL preflight failed: non-numeric value '" + text + "' for numeric param :" + paramName
                                + " on " + table + "." + column);
            }
            params.put(paramName, coerceNumericObject(mapped));
        }
    }

    private String coerceNumericLiterals(
            Map<String, String> aliasToTable,
            Map<String, Map<String, Integer>> tableColumns,
            String sql,
            ConvEngineMcpConfig.Db.Preflight cfg) {
        Matcher matcher = LITERAL_EQUALITY_PATTERN.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String alias = matcher.group(1).toLowerCase(Locale.ROOT);
            String column = matcher.group(2).toLowerCase(Locale.ROOT);
            String literal = matcher.group(3);
            String table = aliasToTable.get(alias);
            if (table == null) {
                continue;
            }
            Integer dataType = tableColumns.getOrDefault(table, Map.of()).get(column);
            if (!isNumericType(dataType) || isNumericText(literal)) {
                continue;
            }
            String mapped = lookupMapping(cfg, table, column, literal);
            if (mapped == null) {
                throw new IllegalArgumentException(
                        "SQL preflight failed: non-numeric literal '" + literal + "' for numeric column " + table + "." + column);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    matcher.group(1) + "." + matcher.group(2) + " = " + mapped));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean isNumericType(Integer dataType) {
        if (dataType == null) {
            return false;
        }
        return dataType == Types.INTEGER
                || dataType == Types.BIGINT
                || dataType == Types.SMALLINT
                || dataType == Types.TINYINT
                || dataType == Types.NUMERIC
                || dataType == Types.DECIMAL
                || dataType == Types.FLOAT
                || dataType == Types.DOUBLE
                || dataType == Types.REAL;
    }

    private boolean isNumericText(String value) {
        return value != null && value.matches("[-+]?[0-9]+(\\.[0-9]+)?");
    }

    private Object coerceNumericObject(String mapped) {
        if (mapped == null) {
            return null;
        }
        if (mapped.matches("[-+]?[0-9]+")) {
            try {
                return Long.parseLong(mapped);
            } catch (NumberFormatException ignored) {
                return mapped;
            }
        }
        if (mapped.matches("[-+]?[0-9]+\\.[0-9]+")) {
            try {
                return Double.parseDouble(mapped);
            } catch (NumberFormatException ignored) {
                return mapped;
            }
        }
        return mapped;
    }

    private String lookupMapping(ConvEngineMcpConfig.Db.Preflight cfg, String table, String column, String raw) {
        if (cfg == null || cfg.getValueMappings() == null || cfg.getValueMappings().isEmpty() || raw == null) {
            return null;
        }
        String key1 = (table + "." + column).toLowerCase(Locale.ROOT);
        String key2 = column.toLowerCase(Locale.ROOT);
        String candidate = raw.trim();

        Map<String, String> byFull = cfg.getValueMappings().get(key1);
        if (byFull != null) {
            String mapped = findMappedValue(byFull, candidate);
            if (mapped != null) {
                return mapped;
            }
        }
        Map<String, String> byColumn = cfg.getValueMappings().get(key2);
        if (byColumn != null) {
            return findMappedValue(byColumn, candidate);
        }
        return null;
    }

    private String findMappedValue(Map<String, String> mappings, String value) {
        for (Map.Entry<String, String> e : mappings.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(value)) {
                return e.getValue();
            }
        }
        return null;
    }

    private String normalizeTableName(String tableRef) {
        if (tableRef == null || tableRef.isBlank()) {
            return null;
        }
        return tableRef.toLowerCase(Locale.ROOT).trim();
    }

    private String stripSchema(String tableName) {
        int idx = tableName.lastIndexOf('.');
        if (idx < 0) {
            return tableName;
        }
        return tableName.substring(idx + 1);
    }

    private String requireSafeIdentifier(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_$.]+")) {
            throw new IllegalArgumentException("Unsafe SQL identifier in preflight config: " + value);
        }
        return value;
    }

    private Set<String> normalizeAllowTables(List<String> tables) {
        Set<String> allow = new LinkedHashSet<>();
        for (String t : tables) {
            if (t != null) {
                allow.add(normalizeTableName(t));
            }
        }
        return allow;
    }

    private String readRowValueCaseInsensitive(Map<String, Object> row, String targetKey) {
        if (row == null || row.isEmpty() || targetKey == null) {
            return null;
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(targetKey) && e.getValue() != null) {
                return String.valueOf(e.getValue());
            }
        }
        return null;
    }

    public record PreflightResult(String sql, Map<String, Object> params) {
    }

    public record RepairContext(Map<String, Object> schemaDetails, Map<String, Object> semanticHints) {
    }
}
