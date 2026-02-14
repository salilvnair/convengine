package com.github.salilvnair.convengine.audit.dispatch;

import com.github.salilvnair.convengine.config.ConvEngineAuditConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class AuditStageControl {

    private static final String STEP_ENTER = "STEP_ENTER";
    private static final String STEP_EXIT = "STEP_EXIT";

    private final ConvEngineAuditConfig auditConfig;
    private final Map<String, WindowCounter> rateBuckets = new ConcurrentHashMap<>();
    private final AtomicLong droppedByRate = new AtomicLong();

    public boolean shouldAudit(String stage, UUID conversationId) {
        if (!auditConfig.isEnabled()) {
            return false;
        }
        String normalized = normalize(stage);
        if (!allowByLevel(normalized)) {
            return false;
        }
        if (!allowByIncludeExclude(normalized)) {
            return false;
        }
        return allowByRateLimit(normalized, conversationId);
    }

    public long droppedByRateCount() {
        return droppedByRate.get();
    }

    private boolean allowByLevel(String stage) {
        ConvEngineAuditConfig.Level level = auditConfig.getLevel();
        if (level == null) {
            return true;
        }
        return switch (level) {
            case ALL -> true;
            case NONE -> false;
            case ERROR_ONLY -> isErrorStage(stage);
            case STANDARD -> !STEP_ENTER.equals(stage) && !STEP_EXIT.equals(stage);
        };
    }

    private boolean allowByIncludeExclude(String stage) {
        if (!auditConfig.getIncludeStages().isEmpty()) {
            boolean anyMatch = auditConfig.getIncludeStages().stream()
                    .map(this::normalize)
                    .anyMatch(pattern -> matches(pattern, stage));
            if (!anyMatch) {
                return false;
            }
        }
        if (!auditConfig.getExcludeStages().isEmpty()) {
            boolean excluded = auditConfig.getExcludeStages().stream()
                    .map(this::normalize)
                    .anyMatch(pattern -> matches(pattern, stage));
            if (excluded) {
                return false;
            }
        }
        return true;
    }

    private boolean allowByRateLimit(String stage, UUID conversationId) {
        ConvEngineAuditConfig.RateLimit rl = auditConfig.getRateLimit();
        if (rl == null || !rl.isEnabled()) {
            return true;
        }
        long windowMs = Math.max(1L, rl.getWindowMs());
        int maxEvents = Math.max(1, rl.getMaxEvents());
        String key = bucketKey(stage, conversationId, rl.isPerConversation(), rl.isPerStage());

        if (!rateBuckets.containsKey(key) && rateBuckets.size() >= Math.max(1000, rl.getMaxTrackedBuckets())) {
            droppedByRate.incrementAndGet();
            return false;
        }

        WindowCounter counter = rateBuckets.computeIfAbsent(key, ignored -> new WindowCounter(System.currentTimeMillis()));
        long now = System.currentTimeMillis();
        synchronized (counter) {
            if (now - counter.windowStartMs >= windowMs) {
                counter.windowStartMs = now;
                counter.count = 0;
            }
            if (counter.count >= maxEvents) {
                droppedByRate.incrementAndGet();
                return false;
            }
            counter.count++;
            return true;
        }
    }

    private String bucketKey(String stage, UUID conversationId, boolean perConversation, boolean perStage) {
        String convKey = perConversation && conversationId != null ? conversationId.toString() : "*";
        String stageKey = perStage ? stage : "*";
        return convKey + "|" + stageKey;
    }

    private boolean isErrorStage(String stage) {
        return stage.contains("ERROR") || stage.contains("FAIL") || stage.contains("REJECTED");
    }

    private boolean matches(String pattern, String stage) {
        if (pattern.isBlank()) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }
        if (!pattern.contains("*")) {
            return pattern.equals(stage);
        }
        StringBuilder regex = new StringBuilder("^");
        String[] parts = pattern.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            regex.append(java.util.regex.Pattern.quote(parts[i]));
            if (i < parts.length - 1) {
                regex.append(".*");
            }
        }
        regex.append("$");
        return stage.matches(regex.toString());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static final class WindowCounter {
        private long windowStartMs;
        private int count;

        private WindowCounter(long windowStartMs) {
            this.windowStartMs = windowStartMs;
        }
    }
}
