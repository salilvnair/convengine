package com.github.salilvnair.convengine.engine.mcp.query.semantic.graph;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.CandidateTable;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.RetrievalResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class DefaultJoinPathResolver implements JoinPathResolver {

    private final ConvEngineMcpConfig mcpConfig;
    private final ObjectProvider<List<SchemaGraphEngine>> graphEnginesProvider;
    private final ObjectProvider<List<SchemaGraphInterceptor>> interceptorsProvider;

    @Override
    public JoinPathPlan resolve(RetrievalResult retrieval, EngineSession session) {
        List<SchemaGraphInterceptor> interceptors = interceptorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(interceptors);
        for (SchemaGraphInterceptor interceptor : interceptors) {
            if (interceptor != null && interceptor.supports(session)) {
                interceptor.beforeResolve(retrieval, session);
            }
        }
        try {
            JoinPathPlan plan = doResolve(retrieval, session);
            JoinPathPlan current = plan;
            for (SchemaGraphInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    current = interceptor.afterResolve(current, session);
                }
            }
            return current;
        } catch (Exception ex) {
            for (SchemaGraphInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    interceptor.onError(retrieval, session, ex);
                }
            }
            throw ex;
        }
    }

    private JoinPathPlan doResolve(RetrievalResult retrieval, EngineSession session) {
        List<CandidateTable> candidates = retrieval == null ? List.of() : retrieval.candidateTables();
        if (candidates.isEmpty()) {
            return new JoinPathPlan("", List.of(), List.of(), List.of(), 0.0d);
        }

        SchemaGraphEngine graph = resolveGraphEngine();
        if (graph == null) {
            return new JoinPathPlan(candidates.get(0).name(), List.of(), List.of(candidates.get(0).name()), List.of(), 0.5d);
        }

        String base = candidates.get(0).name();
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        int maxHops = cfg.getMaxJoinHops();

        Set<String> requiredTables = new LinkedHashSet<>();
        for (CandidateTable candidate : candidates) {
            requiredTables.add(candidate.name());
        }

        List<SchemaEdge> merged = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        for (String target : requiredTables) {
            if (base.equalsIgnoreCase(target)) {
                continue;
            }
            List<SchemaEdge> path = graph.shortestPath(base, target, maxHops);
            if (path == null || path.isEmpty()) {
                unresolved.add(target);
                continue;
            }
            for (SchemaEdge edge : path) {
                if (!containsEdge(merged, edge)) {
                    merged.add(edge);
                }
            }
        }

        double confidence = unresolved.isEmpty() ? 1.0d : 0.6d;
        return new JoinPathPlan(base, List.copyOf(merged), List.copyOf(requiredTables), List.copyOf(unresolved), confidence);
    }

    private boolean containsEdge(List<SchemaEdge> edges, SchemaEdge candidate) {
        for (SchemaEdge edge : edges) {
            if (edge.leftTable().equalsIgnoreCase(candidate.leftTable())
                    && edge.leftColumn().equalsIgnoreCase(candidate.leftColumn())
                    && edge.rightTable().equalsIgnoreCase(candidate.rightTable())
                    && edge.rightColumn().equalsIgnoreCase(candidate.rightColumn())) {
                return true;
            }
        }
        return false;
    }

    private SchemaGraphEngine resolveGraphEngine() {
        List<SchemaGraphEngine> engines = graphEnginesProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(engines);
        String adapter = mcpConfig.getDb() == null || mcpConfig.getDb().getSemantic() == null || mcpConfig.getDb().getSemantic().getGraph() == null
                ? "jgrapht"
                : mcpConfig.getDb().getSemantic().getGraph().getAdapter();
        for (SchemaGraphEngine engine : engines) {
            if (engine != null && engine.supports(adapter)) {
                return engine;
            }
        }
        return null;
    }
}
