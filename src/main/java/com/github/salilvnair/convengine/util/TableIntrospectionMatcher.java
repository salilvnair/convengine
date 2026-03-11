package com.github.salilvnair.convengine.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class TableIntrospectionMatcher {

    private TableIntrospectionMatcher() {
    }

    public static List<String> normalizePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : patterns) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return out;
    }

    public static boolean hasPatterns(List<String> patterns) {
        return patterns != null && !patterns.isEmpty();
    }

    public static boolean matches(String tableName, List<String> patterns) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        if (!hasPatterns(patterns)) {
            return true;
        }
        String normalized = tableName.trim().toLowerCase(Locale.ROOT);
        for (String rule : patterns) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            if (isWildcard(rule)) {
                Pattern p = wildcardPattern(rule);
                if (p.matcher(normalized).matches()) {
                    return true;
                }
            } else if (normalized.equals(rule.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static String toSqlLikePattern(String rule) {
        if (rule == null) {
            return "";
        }
        return rule.trim().replace("*", "%");
    }

    public static String toMetadataPattern(String rule) {
        return toSqlLikePattern(rule);
    }

    public static boolean isWildcard(String rule) {
        return rule != null && rule.contains("*");
    }

    public static boolean matchesQuery(String tableName, String query, String matchMode) {
        String table = tableName == null ? "" : tableName.trim();
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return true;
        }
        String mode = matchMode == null ? "REGEX" : matchMode.trim().toUpperCase(Locale.ROOT);
        if ("EXACT".equals(mode)) {
            return table.equalsIgnoreCase(q);
        }
        try {
            return Pattern.compile(q, Pattern.CASE_INSENSITIVE).matcher(table).find();
        } catch (Exception ignore) {
            return table.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));
        }
    }

    private static Pattern wildcardPattern(String wildcard) {
        StringBuilder out = new StringBuilder("^");
        String s = wildcard == null ? "" : wildcard.toLowerCase(Locale.ROOT);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '*') {
                out.append(".*");
            } else {
                if ("\\.[]{}()+-^$|?".indexOf(ch) >= 0) {
                    out.append("\\");
                }
                out.append(ch);
            }
        }
        out.append("$");
        return Pattern.compile(out.toString(), Pattern.CASE_INSENSITIVE);
    }
}

