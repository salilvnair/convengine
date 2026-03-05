package com.github.salilvnair.convengine.engine.factory;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.AuditSessionContext;
import com.github.salilvnair.convengine.engine.core.step.CoreStepDagOrderer;
import com.github.salilvnair.convengine.engine.core.step.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.hook.EngineStepHook;
import com.github.salilvnair.convengine.engine.model.StepTiming;
import com.github.salilvnair.convengine.engine.pipeline.EnginePipeline;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class EnginePipelineFactory {

    private static final Logger log = LoggerFactory.getLogger(EnginePipelineFactory.class);

    private final List<EngineStep> discoveredSteps;
    private final List<EngineStepHook> stepHooks;
    private final AuditService audit;
    private final CoreStepDagOrderer dagOrderer;

    private EnginePipeline pipeline;

    @Autowired
    public EnginePipelineFactory(
            List<EngineStep> discoveredSteps,
            List<EngineStepHook> stepHooks,
            AuditService audit
    ) {
        this(discoveredSteps, stepHooks, audit, new CoreStepDagOrderer());
    }

    public EnginePipelineFactory(
            List<EngineStep> discoveredSteps,
            List<EngineStepHook> stepHooks,
            AuditService audit,
            CoreStepDagOrderer dagOrderer
    ) {
        this.discoveredSteps = discoveredSteps;
        this.stepHooks = stepHooks;
        this.audit = audit;
        this.dagOrderer = dagOrderer;
    }

    // ---------------------------------------------------------------------
    // Init
    // ---------------------------------------------------------------------
    @PostConstruct
    public void init() {
        try {
            List<EngineStep> ordered = dagOrderer.order(
                    discoveredSteps,
                    EngineStep.class,
                    "ConvEngine pipeline",
                    c -> c.getAnnotation(RequiresConversationPersisted.class) != null
            );
            debugPrint(ordered);
            this.pipeline = new EnginePipeline(wrapWithTiming(ordered));
        } catch (ConversationEngineException ex) {
            throw ex;
        } catch (IllegalStateException ex) {
            ConversationEngineException wrapped = new ConversationEngineException(resolveDagErrorCode(ex), ex.getMessage());
            wrapped.initCause(ex);
            throw wrapped;
        }
    }

    private ConversationEngineErrorCode resolveDagErrorCode(IllegalStateException ex) {
        String msg = ex == null || ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        if (msg.contains("duplicate step")) {
            return ConversationEngineErrorCode.DUPLICATE_ENGINE_STEP;
        }
        if (msg.contains("start step")) {
            return ConversationEngineErrorCode.MISSING_BOOTSTRAP_STEP;
        }
        if (msg.contains("terminal step")) {
            return ConversationEngineErrorCode.MISSING_TERMINAL_STEP;
        }
        if (msg.contains("depends on missing")) {
            return ConversationEngineErrorCode.MISSING_DEPENDENT_STEP;
        }
        if (msg.contains("dag cycle")) {
            return ConversationEngineErrorCode.MISSING_DAG_CYCLE;
        }
        return ConversationEngineErrorCode.PIPELINE_CONSTRAINT_VIOLATION;
    }

    public EnginePipeline create() {
        return pipeline;
    }

    // ---------------------------------------------------------------------
    // Debug print
    // ---------------------------------------------------------------------
    private void debugPrint(List<EngineStep> ordered) {
        log.info(
                "ConvEngine pipeline order: {}",
                ordered.stream()
                        .map(s -> s.getClass().getSimpleName())
                        .collect(Collectors.joining(" -> "))
        );
    }

    // ---------------------------------------------------------------------
    // Timing wrapper
    // ---------------------------------------------------------------------
    private List<EngineStep> wrapWithTiming(List<EngineStep> steps) {
        Map<Class<?>, EngineStep> wrapped = new ConcurrentHashMap<>();
        for (EngineStep step : steps) {
            wrapped.put(step.getClass(), new TimingEngineStep(step, stepHooks, audit));
        }
        return steps.stream().map(s -> wrapped.get(s.getClass())).toList();
    }

    private static final class TimingEngineStep implements EngineStep {

        private final EngineStep delegate;
        private final List<EngineStepHook> stepHooks;
        private final AuditService audit;

        private TimingEngineStep(
                EngineStep delegate,
                List<EngineStepHook> stepHooks,
                AuditService audit
        ) {
            this.delegate = delegate;
            this.stepHooks = stepHooks == null ? List.of() : stepHooks;
            this.audit = audit;
        }

        @Override
        public StepResult execute(EngineSession session) {
            long start = System.nanoTime();
            String stepName = delegate.getClass().getSimpleName();
            EngineStep.Name typedStepName = EngineStep.Name.fromStepName(stepName);
            String stepClass = delegate.getClass().getName();
            AuditSessionContext.set(session);

            StepTiming timing = StepTiming.builder()
                    .stepName(stepName)
                    .startedAtNs(start)
                    .success(false)
                    .build();
            session.recordStepEnter(stepName, stepClass, "STEP_ENTER", start,
                    stepMetaMap(session));

            audit.audit(
                    "STEP_ENTER",
                    session.getConversationId(),
                    stepAuditPayload(session, stepName, stepClass, Map.of())
            );
            for (EngineStepHook hook : stepHooks) {
                runHookSafely(() -> {
                    if (hook.supports(typedStepName, session)) {
                        hook.beforeStep(typedStepName, session);
                    }
                }, hook, "beforeStep", stepName, session);
            }

            try {
                StepResult r = delegate.execute(session);
                long end = System.nanoTime();
                timing.setEndedAtNs(end);
                timing.setDurationMs((end - start) / 1_000_000);
                timing.setSuccess(true);
                session.getStepTimings().add(timing);
                session.recordStepExit(stepName, "STEP_EXIT", end, timing.getDurationMs(), r.getClass().getSimpleName(),
                        stepMetaMap(session),
                        mapOfNullable("resultType", r.getClass().getSimpleName()));
                for (EngineStepHook hook : stepHooks) {
                    runHookSafely(() -> {
                        if (hook.supports(typedStepName, session)) {
                            hook.afterStep(typedStepName, session, r);
                        }
                    }, hook, "afterStep", stepName, session);
                }
                audit.audit(
                        "STEP_EXIT",
                        session.getConversationId(),
                        stepAuditPayload(
                                session,
                                stepName,
                                stepClass,
                                Map.of(
                                        "outcome", r.getClass().getSimpleName(),
                                        "durationMs", timing.getDurationMs()
                                )
                        )
                );
                return r;
            } catch (Exception e) {
                long end = System.nanoTime();
                timing.setEndedAtNs(end);
                timing.setDurationMs((end - start) / 1_000_000);
                timing.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
                session.getStepTimings().add(timing);
                session.recordStepError(stepName, "STEP_ERROR", end, timing.getDurationMs(), e,
                        stepMetaMap(session),
                        mapOfNullable(
                                "errorType", e.getClass().getSimpleName(),
                                "errorMessage", String.valueOf(e.getMessage())));
                for (EngineStepHook hook : stepHooks) {
                    runHookSafely(() -> {
                        if (hook.supports(typedStepName, session)) {
                            hook.onStepError(typedStepName, session, e);
                        }
                    }, hook, "onStepError", stepName, session);
                }
                Map<String, Object> errorPayload = new LinkedHashMap<>();
                errorPayload.put("step", stepName);
                errorPayload.put("stepClass", stepClass);
                errorPayload.put("durationMs", timing.getDurationMs());
                errorPayload.put("errorType", e.getClass().getSimpleName());
                errorPayload.put("errorMessage", String.valueOf(e.getMessage()));
                if(e instanceof ConversationEngineException) {
                    Map<String, Object> metaData = ((ConversationEngineException) e).getMetaData();
                    if (metaData != null) {
                        errorPayload.put("_errorMeta", metaData);
                    }
                }
                audit.audit(
                        "STEP_ERROR",
                        session.getConversationId(),
                        errorPayload
                );
                throw e;
            } finally {
                AuditSessionContext.clear();
            }
        }

        private void runHookSafely(
                Runnable hookCall,
                EngineStepHook hook,
                String phase,
                String stepName,
                EngineSession session
        ) {
            try {
                hookCall.run();
            } catch (Exception ex) {
                log.warn(
                        "EngineStepHook {} failed during {} for step {} convId={}: {}",
                        hook.getClass().getSimpleName(),
                        phase,
                        stepName,
                        session.getConversationId(),
                        ex.getMessage()
                );
                audit.audit(
                        "STEP_HOOK_ERROR",
                        session.getConversationId(),
                        Map.of(
                                "step", stepName,
                                "phase", phase,
                                "hookClass", hook.getClass().getName(),
                                "errorType", ex.getClass().getSimpleName(),
                                "errorMessage", String.valueOf(ex.getMessage())
                        )
                );
            }
        }

        private Map<String, Object> stepAuditPayload(
                EngineSession session,
                String stepName,
                String stepClass,
                Map<String, Object> extra
        ) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(ConvEnginePayloadKey.STEP, stepName);
            payload.put(ConvEnginePayloadKey.STEP_CLASS, stepClass);
            payload.putAll(extra);

            // Add intent/state inside payload _meta so they are visible with stage metadata.
            Map<String, Object> stepMeta = new LinkedHashMap<>();
            stepMeta.put("intent", session.getIntent());
            stepMeta.put("state", session.getState());
            payload.put(ConvEnginePayloadKey.META, stepMeta);

            return payload;
        }

        private Map<String, Object> stepMetaMap(EngineSession session) {
            return mapOfNullable(
                    "intent", session.getIntent(),
                    "state", session.getState());
        }

        private Map<String, Object> mapOfNullable(Object... keyValues) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (keyValues == null) {
                return out;
            }
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                out.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
            return out;
        }
    }
}
