package com.github.salilvnair.convengine.engine.core.step;

import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.core.step.annotation.StartStep;
import com.github.salilvnair.convengine.engine.core.step.annotation.TerminalStep;
import com.github.salilvnair.convengine.engine.core.step.annotation.ConversationBootstrapStep;
import jakarta.annotation.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Component
public class CoreStepDagOrderer {

    public <T> List<T> order(
            List<T> discovered,
            Class<?> contractType,
            String pipelineName,
            @Nullable Predicate<Class<?>> requireStartDependency
    ) {
        // --------------------------------------------
        // Map step class -> bean (1:1 enforced)
        // --------------------------------------------
        Map<Class<?>, T> byClass = new HashMap<>();
        for (T step : discovered) {
            Class<?> cls = step.getClass();
            if (!contractType.isAssignableFrom(cls)) {
                throw new IllegalStateException("Step class " + cls.getName() + " does not implement/extend " + contractType.getName());
            }
            if (byClass.put(cls, step) != null) {
                throw new IllegalStateException("Duplicate step bean for class: " + cls.getName());
            }
        }

        // --------------------------------------------
        // Start-step enforcement (EXACTLY ONE)
        // --------------------------------------------
        List<Class<?>> starts = byClass.keySet().stream().filter(this::isStart).toList();
        if (starts.size() != 1) {
            throw new IllegalStateException("Exactly one start step required for " + pipelineName + ", found: "
                    + starts.stream().map(Class::getSimpleName).collect(Collectors.joining(", ")));
        }
        Class<?> start = starts.getFirst();

        // --------------------------------------------
        // Terminal-step enforcement (EXACTLY ONE)
        // --------------------------------------------
        List<Class<?>> terminals = byClass.keySet().stream().filter(this::isTerminal).toList();
        if (terminals.size() != 1) {
            throw new IllegalStateException("Exactly one terminal step required for " + pipelineName + ", found: "
                    + terminals.stream().map(Class::getSimpleName).collect(Collectors.joining(", ")));
        }
        Class<?> terminal = terminals.getFirst();

        // --------------------------------------------
        // DAG structures
        // --------------------------------------------
        Map<Class<?>, Set<Class<?>>> outgoing = new HashMap<>();
        Map<Class<?>, Set<Class<?>>> incoming = new HashMap<>();
        for (Class<?> c : byClass.keySet()) {
            outgoing.put(c, new LinkedHashSet<>());
            incoming.put(c, new LinkedHashSet<>());
        }

        // --------------------------------------------
        // @MustRunBefore
        // --------------------------------------------
        for (Class<?> c : byClass.keySet()) {
            for (Class<?> before : beforeDependencies(c)) {
                requirePresent(byClass, c, before);
                addEdge(outgoing, incoming, c, before);
            }
        }

        // --------------------------------------------
        // @MustRunAfter (A must run after B => B -> A)
        // --------------------------------------------
        for (Class<?> c : byClass.keySet()) {
            for (Class<?> after : afterDependencies(c)) {
                requirePresent(byClass, c, after);
                addEdge(outgoing, incoming, after, c);
            }
        }

        // --------------------------------------------
        // Start-dependent edges
        // Example: RequiresConversationPersisted semantics
        // Enforce: start -> step
        // --------------------------------------------
        Predicate<Class<?>> startDependency = requireStartDependency == null ? c -> false : requireStartDependency;
        for (Class<?> c : byClass.keySet()) {
            if (startDependency.test(c) && !c.equals(start)) {
                addEdge(outgoing, incoming, start, c);
            }
        }

        // --------------------------------------------
        // Boundary enforcement
        // Keep terminal as global last boundary.
        // Start is NOT forced ahead of all nodes because
        // bootstrap/start steps may legitimately declare
        // @MustRunAfter dependencies.
        // --------------------------------------------
        for (Class<?> c : byClass.keySet()) {
            if (!c.equals(terminal)) {
                addEdge(outgoing, incoming, c, terminal);
            }
        }

        // --------------------------------------------
        // Topological sort
        // --------------------------------------------
        List<Class<?>> sorted = topoSort(byClass.keySet(), outgoing, incoming, pipelineName);
        return sorted.stream().map(byClass::get).toList();
    }

    private boolean isStart(Class<?> c) {
        return c.getAnnotation(StartStep.class) != null
                || c.getAnnotation(ConversationBootstrapStep.class) != null;
    }

    private boolean isTerminal(Class<?> c) {
        return c.getAnnotation(TerminalStep.class) != null;
    }

    private List<Class<?>> beforeDependencies(Class<?> c) {
        List<Class<?>> deps = new ArrayList<>();
        MustRunBefore generic = c.getAnnotation(MustRunBefore.class);
        if (generic != null) {
            deps.addAll(List.of(generic.value()));
        }
        return deps;
    }

    private List<Class<?>> afterDependencies(Class<?> c) {
        List<Class<?>> deps = new ArrayList<>();
        MustRunAfter generic = c.getAnnotation(MustRunAfter.class);
        if (generic != null) {
            deps.addAll(List.of(generic.value()));
        }
        return deps;
    }

    private void requirePresent(Map<Class<?>, ?> byClass, Class<?> owner, Class<?> dep) {
        if (!byClass.containsKey(dep)) {
            throw new IllegalStateException(owner.getSimpleName() + " depends on missing step: " + dep.getName());
        }
    }

    private void addEdge(Map<Class<?>, Set<Class<?>>> outgoing, Map<Class<?>, Set<Class<?>>> incoming, Class<?> from, Class<?> to) {
        if (from.equals(to)) {
            return;
        }
        if (outgoing.get(from).add(to)) {
            incoming.get(to).add(from);
        }
    }

    private List<Class<?>> topoSort(Set<Class<?>> nodes, Map<Class<?>, Set<Class<?>>> outgoing, Map<Class<?>, Set<Class<?>>> incoming, String pipelineName) {
        Map<Class<?>, Integer> indegree = new HashMap<>();
        for (Class<?> n : nodes) {
            indegree.put(n, incoming.get(n).size());
        }

        PriorityQueue<Class<?>> q = new PriorityQueue<>(Comparator.comparing(Class::getName));
        indegree.forEach((k, v) -> {
            if (v == 0) {
                q.add(k);
            }
        });

        List<Class<?>> result = new ArrayList<>();
        while (!q.isEmpty()) {
            Class<?> n = q.poll();
            result.add(n);
            for (Class<?> m : outgoing.get(n)) {
                indegree.put(m, indegree.get(m) - 1);
                if (indegree.get(m) == 0) {
                    q.add(m);
                }
            }
        }

        if (result.size() != nodes.size()) {
            Set<Class<?>> remaining = new LinkedHashSet<>(nodes);
            result.forEach(remaining::remove);
            throw new IllegalStateException("DAG cycle or unsatisfied constraints in " + pipelineName + ": "
                    + remaining.stream().map(Class::getSimpleName).collect(Collectors.joining(" -> ")));
        }
        return result;
    }
}
