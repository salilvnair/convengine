package com.github.salilvnair.convengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "convengine.audit")
@Getter
@Setter
public class ConvEngineAuditConfig {

    private boolean enabled = true;
    private boolean persistMeta = true;
    private boolean cacheInspector = false;
    private Level level = Level.ALL;
    private Set<String> includeStages = new LinkedHashSet<>();
    private Set<String> excludeStages = new LinkedHashSet<>();
    private RateLimit rateLimit = new RateLimit();
    private Dispatch dispatch = new Dispatch();
    private Persistence persistence = new Persistence();

    public enum Level {
        ALL,
        STANDARD,
        ERROR_ONLY,
        NONE
    }

    @Getter
    @Setter
    public static class Dispatch {
        private boolean asyncEnabled = false;
        private int workerThreads = 2;
        private int queueCapacity = 2000;
        private RejectionPolicy rejectionPolicy = RejectionPolicy.CALLER_RUNS;
        private long keepAliveSeconds = 60;
    }

    public enum RejectionPolicy {
        CALLER_RUNS,
        DROP_NEWEST,
        DROP_OLDEST,
        ABORT
    }

    @Getter
    @Setter
    public static class RateLimit {
        private boolean enabled = false;
        private int maxEvents = 200;
        private long windowMs = 1000;
        private boolean perConversation = true;
        private boolean perStage = true;
        private int maxTrackedBuckets = 20000;
    }

    @Getter
    @Setter
    public static class Persistence {
        private Mode mode = Mode.IMMEDIATE;
        private int jdbcBatchSize = 200;
        private int maxBufferedEvents = 5000;
        private Set<String> flushStages = new LinkedHashSet<>(Set.of("ENGINE_KNOWN_FAILURE", "ENGINE_UNKNOWN_FAILURE"));
        private Set<String> finalStepNames = new LinkedHashSet<>(Set.of("PipelineEndGuardStep"));
        private boolean flushOnStopOutcome = true;
    }

    public enum Mode {
        IMMEDIATE,
        DEFERRED_BULK
    }
}
