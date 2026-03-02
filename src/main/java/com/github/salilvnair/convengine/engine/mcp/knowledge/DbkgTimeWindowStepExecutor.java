package com.github.salilvnair.convengine.engine.mcp.knowledge;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DbkgTimeWindowStepExecutor implements DbkgStepExecutor {

    @Override
    public boolean supports(String executorCode) {
        return "TIME_WINDOW_DERIVER".equalsIgnoreCase(executorCode);
    }

    @Override
    public Map<String, Object> execute(String stepCode, String templateCode, Map<String, Object> config, Map<String, Object> runtime) {
        int hours = parseInt(config.get("hours"), 24);
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) runtime.getOrDefault("args", Map.of());
        if (args.containsKey("hours")) {
            hours = parseInt(args.get("hours"), hours);
        }
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hours", hours);
        out.put("fromTs", from.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        runtime.put("timeWindow", out);
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
