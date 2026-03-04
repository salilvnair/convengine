package com.github.salilvnair.convengine.engine.mcp.knowledge;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DbkgTimeWindowStepExecutor implements DbkgStepExecutor {

    @Override
    public boolean supports(String executorCode) {
        return DbkgConstants.EXECUTOR_TIME_WINDOW_DERIVER.equalsIgnoreCase(executorCode);
    }

    @Override
    public Map<String, Object> execute(String stepCode, String templateCode, Map<String, Object> config,
            Map<String, Object> runtime) {
        int hours = parseInt(config.get(DbkgConstants.KEY_HOURS), 24);
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) runtime.getOrDefault(DbkgConstants.KEY_ARGS, Map.of());
        if (args.containsKey(DbkgConstants.KEY_HOURS)) {
            hours = parseInt(args.get(DbkgConstants.KEY_HOURS), hours);
        }
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(DbkgConstants.KEY_HOURS, hours);
        out.put(DbkgConstants.KEY_FROM_TS, java.sql.Timestamp.from(from.toInstant()));
        runtime.put(DbkgConstants.KEY_TIME_WINDOW, out);
        return out;
    }

    private int parseInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
