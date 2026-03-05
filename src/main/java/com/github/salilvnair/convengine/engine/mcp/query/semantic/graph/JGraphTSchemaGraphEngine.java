package com.github.salilvnair.convengine.engine.mcp.query.semantic.graph;

import com.github.salilvnair.convengine.cache.DbSchemaInspectorService;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@ConditionalOnClass(name = "org.jgrapht.Graph")
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class JGraphTSchemaGraphEngine implements SchemaGraphEngine {

    private final DbSchemaInspectorService dbSchemaInspectorService;
    private final ConvEngineMcpConfig mcpConfig;

    private volatile Graph<String, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
    private volatile Map<String, SchemaEdge> edgeByKey = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        refreshGraph();
    }

    @Override
    public String adapterName() {
        return "jgrapht";
    }

    @Override
    public boolean supports(String adapterKey) {
        return adapterKey == null || adapterKey.isBlank() || "jgrapht".equalsIgnoreCase(adapterKey.trim());
    }

    @Override
    public synchronized void refreshGraph() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        Map<String, Object> inspected = dbSchemaInspectorService.inspect(null, "");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> joins = (List<Map<String, Object>>) inspected.getOrDefault("joins", List.of());

        Graph<String, DefaultEdge> nextGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
        Map<String, SchemaEdge> nextEdges = new LinkedHashMap<>();

        for (Map<String, Object> row : joins) {
            String sourceTable = text(row.get("source_table"));
            String sourceColumn = text(row.get("source_column"));
            String targetTable = text(row.get("target_table"));
            String targetColumn = text(row.get("target_column"));
            if (sourceTable.isBlank() || targetTable.isBlank()) {
                continue;
            }
            nextGraph.addVertex(sourceTable);
            nextGraph.addVertex(targetTable);
            DefaultEdge edge = nextGraph.addEdge(sourceTable, targetTable);
            if (edge != null) {
                String key = sourceTable + "->" + targetTable;
                nextEdges.put(key, new SchemaEdge(sourceTable, sourceColumn, targetTable, targetColumn, "fk_metadata", "INNER"));
            }
            // add reverse edge for traversal flexibility
            DefaultEdge rev = nextGraph.addEdge(targetTable, sourceTable);
            if (rev != null) {
                String revKey = targetTable + "->" + sourceTable;
                nextEdges.put(revKey, new SchemaEdge(targetTable, targetColumn, sourceTable, sourceColumn, "fk_metadata", "INNER"));
            }
        }

        this.graph = nextGraph;
        this.edgeByKey = nextEdges;
        log.info("Semantic schema graph refreshed: vertices={}, edges={}", nextGraph.vertexSet().size(), nextGraph.edgeSet().size());
    }

    @Override
    public List<SchemaEdge> shortestPath(String fromTable, String toTable, int maxHops) {
        if (fromTable == null || toTable == null || fromTable.isBlank() || toTable.isBlank()) {
            return List.of();
        }
        if (fromTable.equalsIgnoreCase(toTable)) {
            return List.of();
        }
        if (!graph.containsVertex(fromTable) || !graph.containsVertex(toTable)) {
            return List.of();
        }

        record NodePath(String node, List<String> path) {}
        java.util.ArrayDeque<NodePath> queue = new java.util.ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(new NodePath(fromTable, List.of(fromTable)));
        visited.add(fromTable);

        while (!queue.isEmpty()) {
            NodePath current = queue.poll();
            if (current.node().equals(toTable)) {
                return toEdges(current.path());
            }
            if (current.path().size() > Math.max(maxHops, 1) + 1) {
                continue;
            }
            for (String next : neighbors(current.node())) {
                if (visited.contains(next)) {
                    continue;
                }
                List<String> nextPath = new ArrayList<>(current.path());
                nextPath.add(next);
                queue.add(new NodePath(next, nextPath));
                visited.add(next);
            }
        }
        return List.of();
    }

    @Override
    public Set<String> neighbors(String table) {
        if (table == null || table.isBlank() || !graph.containsVertex(table)) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (DefaultEdge e : graph.outgoingEdgesOf(table)) {
            out.add(graph.getEdgeTarget(e));
        }
        return out;
    }

    private List<SchemaEdge> toEdges(List<String> path) {
        if (path.size() < 2) {
            return List.of();
        }
        List<SchemaEdge> out = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            String key = path.get(i) + "->" + path.get(i + 1);
            SchemaEdge edge = edgeByKey.get(key);
            if (edge != null) {
                out.add(edge);
            }
        }
        return out;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
