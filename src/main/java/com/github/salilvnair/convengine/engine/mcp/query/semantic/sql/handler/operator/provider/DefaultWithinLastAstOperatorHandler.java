package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstOperator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.AstOperatorHandler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core.OperatorHandlerContext;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order()
public class DefaultWithinLastAstOperatorHandler implements AstOperatorHandler {
    private static final Pattern SHORTHAND_PATTERN = Pattern.compile("^(\\d+)\\s*([a-zA-Z]+)?$");

    @Override
    public boolean supports(AstOperator operator) {
        return operator == AstOperator.WITHIN_LAST;
    }

    @Override
    public Condition buildCondition(Field<Object> field, Object value, AstOperator operator, OperatorHandlerContext context) {
        OffsetDateTime cutoff = resolveCutoff(value, context);
        return DSL.condition("{0} >= {1}", field, context.nextParam(cutoff));
    }

    @Override
    public String buildSql(Field<Object> field, Object value, AstOperator operator, OperatorHandlerContext context) {
        OffsetDateTime cutoff = resolveCutoff(value, context);
        String key = context.nextParamKey(cutoff);
        return field + " >= :" + key;
    }

    @Override
    public String sqlToken(AstOperator operator) {
        return "WITHIN_LAST";
    }

    private OffsetDateTime resolveCutoff(Object value, OperatorHandlerContext context) {
        WithinLastSpec spec = parseSpec(value);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return now.minus(spec.amount(), spec.unit());
    }

    private WithinLastSpec parseSpec(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("WITHIN_LAST requires value");
        }
        if (value instanceof Number n) {
            long hours = Math.max(1L, n.longValue());
            return new WithinLastSpec(hours, ChronoUnit.HOURS);
        }
        if (value instanceof Map<?, ?> map) {
            Object amountRaw = firstNonNull(map.get("amount"), firstNonNull(map.get("value"), map.get("n")));
            Object unitRaw = firstNonNull(map.get("unit"), map.get("granularity"));
            long amount = parsePositiveLong(amountRaw);
            TemporalUnit unit = parseUnit(unitRaw == null ? "hour" : String.valueOf(unitRaw));
            return new WithinLastSpec(amount, unit);
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("WITHIN_LAST requires non-empty value");
        }
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replace("within", "")
                .replace("last", "")
                .replace("_", "")
                .trim();
        Matcher matcher = SHORTHAND_PATTERN.matcher(normalized.replace(" ", ""));
        if (matcher.matches()) {
            long amount = parsePositiveLong(matcher.group(1));
            String unitToken = matcher.group(2);
            TemporalUnit unit = parseUnit(unitToken == null ? "h" : unitToken);
            return new WithinLastSpec(amount, unit);
        }
        String[] parts = normalized.split("\\s+");
        if (parts.length >= 1) {
            long amount = parsePositiveLong(parts[0]);
            String unitToken = parts.length >= 2 ? parts[1] : "hour";
            return new WithinLastSpec(amount, parseUnit(unitToken));
        }
        throw new IllegalArgumentException("Unsupported WITHIN_LAST value: " + value);
    }

    private Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private long parsePositiveLong(Object raw) {
        try {
            long value = Long.parseLong(String.valueOf(raw).trim());
            if (value <= 0) {
                throw new IllegalArgumentException("WITHIN_LAST amount must be > 0");
            }
            return value;
        } catch (Exception ex) {
            throw new IllegalArgumentException("WITHIN_LAST amount must be a positive integer");
        }
    }

    private TemporalUnit parseUnit(String rawUnit) {
        String u = rawUnit == null ? "h" : rawUnit.trim().toLowerCase(Locale.ROOT);
        return switch (u) {
            case "m", "min", "mins", "minute", "minutes" -> ChronoUnit.MINUTES;
            case "h", "hr", "hrs", "hour", "hours" -> ChronoUnit.HOURS;
            case "d", "day", "days" -> ChronoUnit.DAYS;
            case "w", "wk", "wks", "week", "weeks" -> ChronoUnit.WEEKS;
            default -> throw new IllegalArgumentException("Unsupported WITHIN_LAST unit: " + rawUnit);
        };
    }

    private record WithinLastSpec(long amount, TemporalUnit unit) {
    }
}
