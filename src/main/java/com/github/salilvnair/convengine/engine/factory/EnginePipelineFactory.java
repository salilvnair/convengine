package com.github.salilvnair.convengine.engine.factory;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.AuditSessionContext;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.hook.EngineStepHook;
import com.github.salilvnair.convengine.engine.model.StepTiming;
import com.github.salilvnair.convengine.engine.pipeline.EnginePipeline;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.*;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class EnginePipelineFactory {

    private static final Logger log = LoggerFactory.getLogger(EnginePipelineFactory.class);

    private final List<EngineStep> discoveredSteps;
    private final List<EngineStepHook> stepHooks;
    private final AuditService audit;

    private EnginePipeline pipeline;

    // ---------------------------------------------------------------------
    // Init
    // ---------------------------------------------------------------------
    @PostConstruct
    public void init() {
        List<EngineStep> ordered = orderByDag(discoveredSteps);
        debugPrint(ordered);
        this.pipeline = new EnginePipeline(wrapWithTiming(ordered));
    }

    public EnginePipeline create() {
        return pipeline;
    }

    // ---------------------------------------------------------------------
    // DAG ordering using annotations
    // ---------------------------------------------------------------------
    private List<EngineStep> orderByDag(List<EngineStep> steps) {

        // --------------------------------------------
        // Map step class → bean (1:1 enforced)
        // --------------------------------------------
        Map<Class<?>, EngineStep> stepByClass = new HashMap<>();
        for (EngineStep s : steps) {
            if (stepByClass.put(s.getClass(), s) != null) {
                throw new ConversationEngineException(
                        ConversationEngineErrorCode.DUPLICATE_ENGINE_STEP,
                        "Duplicate EngineStep bean for class: " + s.getClass().getName()
                );
            }
        }

        // --------------------------------------------
        // TerminalStep enforcement (EXACTLY ONE)
        // --------------------------------------------
        List<Class<?>> terminalSteps = stepByClass.keySet().stream()
                .filter(c -> c.getAnnotation(TerminalStep.class) != null)
                .toList();

        if (terminalSteps.size() != 1) {
            throw new ConversationEngineException(
                    ConversationEngineErrorCode.MISSING_TERMINAL_STEP,
                    "Exactly ONE @TerminalStep required, found: " +
                            terminalSteps.stream()
                                    .map(Class::getSimpleName)
                                    .collect(Collectors.joining(", "))
            );
        }

        Class<?> terminal = terminalSteps.getFirst();

        // --------------------------------------------
        // ConversationBootstrapStep enforcement (EXACTLY ONE)
        // --------------------------------------------
        List<Class<?>> bootstrapSteps = stepByClass.keySet().stream()
                .filter(c -> c.getAnnotation(ConversationBootstrapStep.class) != null)
                .toList();

        if (bootstrapSteps.size() != 1) {
            throw new ConversationEngineException(
                    ConversationEngineErrorCode.MISSING_BOOTSTRAP_STEP,
                    "Exactly ONE @ConversationBootstrapStep required, found: " +
                            bootstrapSteps.stream()
                                    .map(Class::getSimpleName)
                                    .collect(Collectors.joining(", "))
            );
        }

        Class<?> bootstrap = bootstrapSteps.getFirst();

        // --------------------------------------------
        // DAG structures
        // --------------------------------------------
        Map<Class<?>, Set<Class<?>>> outgoing = new HashMap<>();
        Map<Class<?>, Set<Class<?>>> incoming = new HashMap<>();

        for (Class<?> c : stepByClass.keySet()) {
            outgoing.put(c, new LinkedHashSet<>());
            incoming.put(c, new LinkedHashSet<>());
        }

        // --------------------------------------------
        // @MustRunBefore
        // --------------------------------------------
        for (Class<?> c : stepByClass.keySet()) {
            MustRunBefore before = c.getAnnotation(MustRunBefore.class);
            if (before != null) {
                for (Class<? extends EngineStep> b : before.value()) {
                    requirePresent(stepByClass, c, b);
                    addEdge(outgoing, incoming, c, b);
                }
            }
        }

        // --------------------------------------------
        // @MustRunAfter   (A must run after B ⇒ B → A)
        // --------------------------------------------
        for (Class<?> c : stepByClass.keySet()) {
            MustRunAfter after = c.getAnnotation(MustRunAfter.class);
            if (after != null) {
                for (Class<? extends EngineStep> a : after.value()) {
                    requirePresent(stepByClass, c, a);
                    addEdge(outgoing, incoming, a, c);
                }
            }
        }

        // --------------------------------------------
        // @RequiresConversationPersisted
        // Enforce: bootstrap → step
        // --------------------------------------------
        for (Class<?> c : stepByClass.keySet()) {
            if (c.getAnnotation(RequiresConversationPersisted.class) != null) {
                addEdge(outgoing, incoming, bootstrap, c);
            }
        }

        // --------------------------------------------
        // Terminal must be LAST
        // --------------------------------------------
        for (Class<?> c : stepByClass.keySet()) {
            if (!c.equals(terminal)) {
                addEdge(outgoing, incoming, c, terminal);
            }
        }

        // --------------------------------------------
        // Topological sort
        // --------------------------------------------
        List<Class<?>> sorted = topoSort(stepByClass.keySet(), outgoing, incoming);

        return sorted.stream().map(stepByClass::get).toList();
    }

    private void requirePresent(Map<Class<?>, EngineStep> stepByClass,
                                Class<?> owner,
                                Class<?> dep) {
        if (!stepByClass.containsKey(dep)) {
            throw new ConversationEngineException(
                    ConversationEngineErrorCode.MISSING_DEPENDENT_STEP,
                    owner.getSimpleName() + " depends on missing step: " + dep.getName()
            );
        }
    }

    private void addEdge(Map<Class<?>, Set<Class<?>>> outgoing,
                         Map<Class<?>, Set<Class<?>>> incoming,
                         Class<?> from,
                         Class<?> to) {
        if (from.equals(to)) return;
        if (outgoing.get(from).add(to)) {
            incoming.get(to).add(from);
        }
    }

    private List<Class<?>> topoSort(Set<Class<?>> nodes,
                                    Map<Class<?>, Set<Class<?>>> outgoing,
                                    Map<Class<?>, Set<Class<?>>> incoming) {

        Map<Class<?>, Integer> indegree = new HashMap<>();
        for (Class<?> n : nodes) {
            indegree.put(n, incoming.get(n).size());
        }

        PriorityQueue<Class<?>> q =
                new PriorityQueue<>(Comparator.comparing(Class::getName));

        indegree.forEach((k, v) -> {
            if (v == 0) q.add(k);
        });

        List<Class<?>> result = new ArrayList<>();

        while (!q.isEmpty()) {
            Class<?> n = q.poll();
            result.add(n);

            for (Class<?> m : outgoing.get(n)) {
                indegree.put(m, indegree.get(m) - 1);
                if (indegree.get(m) == 0) q.add(m);
            }
        }

        if (result.size() != nodes.size()) {
            Set<Class<?>> remaining = new LinkedHashSet<>(nodes);
            result.forEach(remaining::remove);
            throw new ConversationEngineException(
                    ConversationEngineErrorCode.MISSING_DAG_CYCLE,
                    "EngineStep DAG cycle or unsatisfied constraints: " +
                            remaining.stream()
                                    .map(Class::getSimpleName)
                                    .collect(Collectors.joining(" -> "))
            );
        }

        return result;
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
    }
}
