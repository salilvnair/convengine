package com.github.salilvnair.convengine.engine.mcp;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared SQL safety checks for MCP DB execution paths.
 * DB access remains read-only, while this guard can be enriched by the
 * ce_mcp_sql_guardrail table to allow or block specific SQL functions/keywords.
 */
@Component
@RequiredArgsConstructor
public class McpSqlGuardrail {

    private static final Pattern LINE_COMMENT = Pattern.compile("--.*?(\\R|$)");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SELECT_INTO = Pattern.compile("\\bselect\\b[\\s\\S]*\\binto\\b");
    private static final Pattern FOR_UPDATE = Pattern.compile("\\bfor\\s+update\\b");
    private static final Pattern FUNCTION_CALL =
            Pattern.compile("\\b(?:[a-z_][a-z0-9_]*\\.)*([a-z_][a-z0-9_]*)\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final Set<String> DEFAULT_FORBIDDEN_TOKENS = Set.of(
            "insert",
            "update",
            "delete",
            "drop",
            "truncate",
            "alter",
            "create",
            "merge",
            "grant",
            "revoke",
            "comment",
            "vacuum",
            "analyze",
            "call",
            "execute",
            "do",
            "copy",
            "refresh",
            "reindex",
            "cluster",
            "discard",
            "lock"
    );

    private static final Set<String> DEFAULT_ALLOWED_FUNCTIONS = Set.of(
            "abs",
            "avg",
            "ceil",
            "ceiling",
            "coalesce",
            "concat",
            "count",
            "current_date",
            "current_time",
            "current_timestamp",
            "date_trunc",
            "extract",
            "floor",
            "greatest",
            "least",
            "length",
            "lower",
            "ltrim",
            "max",
            "min",
            "now",
            "nullif",
            "replace",
            "round",
            "rtrim",
            "split_part",
            "string_agg",
            "substring",
            "substr",
            "sum",
            "to_char",
            "to_date",
            "to_timestamp",
            "trim",
            "upper"
    );

    private static final Set<String> FUNCTION_TOKENS_TO_IGNORE = Set.of(
            "and",
            "any",
            "all",
            "as",
            "asc",
            "between",
            "case",
            "cast",
            "desc",
            "distinct",
            "else",
            "end",
            "exists",
            "fetch",
            "filter",
            "from",
            "group",
            "having",
            "in",
            "interval",
            "join",
            "limit",
            "offset",
            "on",
            "or",
            "order",
            "over",
            "partition",
            "row",
            "rows",
            "select",
            "then",
            "union",
            "values",
            "when",
            "where",
            "with"
    );

    private final NamedParameterJdbcTemplate jdbc;
    private final ConvEngineMcpConfig config;

    public void assertReadOnly(String sql, String source) {
        String normalized = normalize(sql);
        normalized = stripOptionalTrailingSemicolon(normalized);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(source + " SQL is blank.");
        }
        if (normalized.contains(";")) {
            throw new IllegalArgumentException(source + " SQL must be a single statement.");
        }
        if (!(normalized.startsWith("select ") || normalized.startsWith("with "))) {
            throw new IllegalArgumentException(source + " SQL must begin with SELECT or WITH for read-only execution.");
        }
        if (SELECT_INTO.matcher(normalized).find()) {
            throw new IllegalArgumentException(source + " SQL uses SELECT INTO, which is not allowed in read-only mode.");
        }
        if (FOR_UPDATE.matcher(normalized).find()) {
            throw new IllegalArgumentException(source + " SQL uses FOR UPDATE, which is not allowed in read-only mode.");
        }

        CustomRules rules = loadCustomRules();
        Set<String> forbiddenTokens = new LinkedHashSet<>(DEFAULT_FORBIDDEN_TOKENS);
        forbiddenTokens.addAll(rules.blockedKeywords);
        for (String token : forbiddenTokens) {
            if (containsWord(normalized, token)) {
                throw new IllegalArgumentException(source + " SQL contains blocked keyword: " + token.toUpperCase(Locale.ROOT));
            }
        }

        Set<String> functions = detectFunctions(normalized);
        for (String function : functions) {
            if (rules.blockedFunctions.contains(function)) {
                throw new IllegalArgumentException(source + " SQL uses blocked function: " + function.toUpperCase(Locale.ROOT));
            }
        }

        if (!rules.allowedFunctions.isEmpty()) {
            Set<String> permittedFunctions = new LinkedHashSet<>(DEFAULT_ALLOWED_FUNCTIONS);
            permittedFunctions.addAll(rules.allowedFunctions);
            for (String function : functions) {
                if (!permittedFunctions.contains(function)) {
                    throw new IllegalArgumentException(source + " SQL uses function not present in the allowlist: " + function.toUpperCase(Locale.ROOT));
                }
            }
        }
    }

    private CustomRules loadCustomRules() {
        String table = config.getDb().getSqlGuardrailTable();
        if (table == null || table.isBlank()) {
            return CustomRules.EMPTY;
        }
        String sql = "select rule_type, match_value, enabled from " + table;
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql, Map.of());
        } catch (DataAccessException ex) {
            return CustomRules.EMPTY;
        }
        Set<String> allowedFunctions = new LinkedHashSet<>();
        Set<String> blockedFunctions = new LinkedHashSet<>();
        Set<String> blockedKeywords = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (!truthy(row.get("enabled"))) {
                continue;
            }
            String ruleType = asLower(row.get("rule_type"));
            String matchValue = asLower(row.get("match_value"));
            if (matchValue.isBlank()) {
                continue;
            }
            switch (ruleType) {
                case "allow_function" -> allowedFunctions.add(matchValue);
                case "block_function" -> blockedFunctions.add(matchValue);
                case "block_keyword" -> blockedKeywords.add(matchValue);
                default -> {
                }
            }
        }
        return new CustomRules(allowedFunctions, blockedFunctions, blockedKeywords);
    }

    private Set<String> detectFunctions(String normalizedSql) {
        Set<String> functions = new LinkedHashSet<>();
        Matcher matcher = FUNCTION_CALL.matcher(normalizedSql);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token == null) {
                continue;
            }
            String function = token.toLowerCase(Locale.ROOT);
            if (!FUNCTION_TOKENS_TO_IGNORE.contains(function)) {
                functions.add(function);
            }
        }
        return functions;
    }

    private static boolean containsWord(String sql, String token) {
        return Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(sql).find();
    }

    private static String normalize(String sql) {
        String value = sql == null ? "" : sql;
        value = LINE_COMMENT.matcher(value).replaceAll(" ");
        value = BLOCK_COMMENT.matcher(value).replaceAll(" ");
        value = WHITESPACE.matcher(value).replaceAll(" ").trim();
        return value.toLowerCase(Locale.ROOT);
    }

    private static String stripOptionalTrailingSemicolon(String sql) {
        if (sql == null) {
            return "";
        }
        String value = sql.trim();
        if (value.endsWith(";")) {
            return value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private static String asLower(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text)
                || "1".equals(text)
                || "y".equalsIgnoreCase(text)
                || "yes".equalsIgnoreCase(text);
    }

    private record CustomRules(Set<String> allowedFunctions,
                               Set<String> blockedFunctions,
                               Set<String> blockedKeywords) {
        private static final CustomRules EMPTY = new CustomRules(Set.of(), Set.of(), Set.of());
    }
}
