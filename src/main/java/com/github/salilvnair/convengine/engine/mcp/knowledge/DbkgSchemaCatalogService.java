package com.github.salilvnair.convengine.engine.mcp.knowledge;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DbkgSchemaCatalogService {

    private final DataSource dataSource;
    private final DbkgSupportService support;

    public DbkgPhysicalSchemaCatalog discoverPhysicalSchema() {
        if (!support.cfg().isSchemaIntrospectionEnabled()) {
            return new DbkgPhysicalSchemaCatalog(List.of(), List.of(), List.of());
        }
        Map<String, Map<String, Object>> objectOverlays = overlayObjectsByName();
        Map<String, Map<String, Object>> columnOverlays = overlayColumnsByKey();
        List<Map<String, Object>> objects = new ArrayList<>();
        List<Map<String, Object>> columns = new ArrayList<>();
        List<Map<String, Object>> joins = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            Set<String> included = support.normalizeSchemaNames(support.cfg().getIncludedSchemas());
            Set<String> excluded = support.normalizeSchemaNames(support.cfg().getExcludedSchemas());
            int objectCount = 0;

            try (ResultSet tables = metaData.getTables(catalog, null, "%", new String[]{"TABLE", "VIEW"})) {
                while (tables.next() && objectCount < Math.max(1, support.cfg().getSchemaObjectLimit())) {
                    String schemaName = support.safeSchema(tables.getString("TABLE_SCHEM"));
                    if (!support.isAllowedSchema(schemaName, included, excluded)) {
                        continue;
                    }
                    String tableName = tables.getString("TABLE_NAME");
                    String tableType = tables.getString("TABLE_TYPE");
                    Map<String, Object> object = new LinkedHashMap<>();
                    object.put("objectName", tableName);
                    object.put("schemaName", schemaName);
                    object.put("objectType", tableType);
                    object.put("accessMode", "READ_ONLY");
                    applyObjectOverlay(object, objectOverlays.get(support.normalizeKey(tableName)));
                    objects.add(object);
                    objectCount++;

                    Set<String> primaryKeys = readPrimaryKeys(metaData, catalog, schemaName, tableName);
                    try (ResultSet cols = metaData.getColumns(catalog, schemaName, tableName, "%")) {
                        while (cols.next()) {
                            String columnName = cols.getString("COLUMN_NAME");
                            Map<String, Object> column = new LinkedHashMap<>();
                            column.put("objectName", tableName);
                            column.put("schemaName", schemaName);
                            column.put("columnName", columnName);
                            column.put("semanticName", support.toLowerSnake(columnName));
                            column.put("dataType", cols.getString("TYPE_NAME"));
                            column.put("nullableFlag", DatabaseMetaData.columnNullable == cols.getInt("NULLABLE"));
                            column.put("keyType", primaryKeys.contains(columnName.toLowerCase()) ? "PRIMARY" : "NONE");
                            applyColumnOverlay(column, columnOverlays.get(support.normalizeColumnKey(tableName, columnName)));
                            columns.add(column);
                        }
                    }

                    try (ResultSet imported = metaData.getImportedKeys(catalog, schemaName, tableName)) {
                        while (imported.next()) {
                            String pkTable = imported.getString("PKTABLE_NAME");
                            String fkTable = imported.getString("FKTABLE_NAME");
                            String pkColumn = imported.getString("PKCOLUMN_NAME");
                            String fkColumn = imported.getString("FKCOLUMN_NAME");
                            if (pkTable == null || fkTable == null || pkColumn == null || fkColumn == null) {
                                continue;
                            }
                            Map<String, Object> join = new LinkedHashMap<>();
                            join.put("joinName", pkTable + "_to_" + fkTable + "_" + fkColumn);
                            join.put("leftObjectName", pkTable);
                            join.put("rightObjectName", fkTable);
                            join.put("joinType", "INNER");
                            join.put("joinSqlFragment", pkTable + "." + pkColumn + " = " + fkTable + "." + fkColumn);
                            join.put("businessReason", "Auto-discovered foreign key join via JDBC metadata.");
                            join.put("confidenceScore", 1.0d);
                            joins.add(join);
                        }
                    }
                }
            }
        } catch (Exception e) {
            return new DbkgPhysicalSchemaCatalog(List.of(), List.of(), List.of());
        }

        for (Map<String, Object> overlay : objectOverlays.values()) {
            String objectName = support.asText(overlay.get("object_name"));
            if (objectName.isBlank() || containsObject(objects, objectName)) {
                continue;
            }
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("objectName", objectName);
            object.put("schemaName", "");
            object.put("objectType", support.asText(overlay.get("object_type")));
            object.put("accessMode", support.asText(overlay.get("access_mode")));
            applyObjectOverlay(object, overlay);
            objects.add(object);
        }
        return new DbkgPhysicalSchemaCatalog(
                deduplicateByKey(objects, "objectName"),
                deduplicateColumns(columns),
                deduplicateByKey(joins, "joinName"));
    }

    public List<Map<String, Object>> rankDiscoveredDbObjects(DbkgPhysicalSchemaCatalog physical, List<String> tokens) {
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> row : physical.objects()) {
            List<String> docTokens = new ArrayList<>();
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("objectName"))));
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("schemaName"))));
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("description"))));
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("metadataJson"))));
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("llmHint"))));
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("entityCode"))));
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("systemCode"))));
            double score = tokens.isEmpty() ? 0.0d : support.score(tokens, docTokens);
            if (score <= 0.0d) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("score", support.round(score));
            ranked.add(item);
        }
        ranked.sort(Comparator.comparingDouble(row -> -support.parseDouble(row.get("score"), 0.0d)));
        int max = Math.max(1, support.cfg().getMaxResults());
        return ranked.size() <= max ? ranked : ranked.subList(0, max);
    }

    public List<Map<String, Object>> rankDiscoveredDbColumns(
            DbkgPhysicalSchemaCatalog physical,
            List<String> tokens,
            List<Map<String, Object>> rankedObjects) {
        Set<String> preferredObjects = new LinkedHashSet<>();
        for (Map<String, Object> item : rankedObjects) {
            preferredObjects.add(support.asText(item.get("objectName")));
        }
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> row : physical.columns()) {
            if (!preferredObjects.isEmpty() && !preferredObjects.contains(support.asText(row.get("objectName")))) {
                continue;
            }
            List<String> docTokens = new ArrayList<>();
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("objectName"))));
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("columnName"))));
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("semanticName"))));
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("synonyms"))));
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("description"))));
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("metadataJson"))));
            docTokens.addAll(support.normalizeTokens(support.asText(row.get("llmHint"))));
            double score = tokens.isEmpty() ? 0.0d : support.score(tokens, docTokens);
            if (score <= 0.0d) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("score", support.round(score));
            ranked.add(item);
        }
        ranked.sort(Comparator.comparingDouble(row -> -support.parseDouble(row.get("score"), 0.0d)));
        int max = Math.max(1, support.cfg().getMaxResults());
        return ranked.size() <= max ? ranked : ranked.subList(0, max);
    }

    public List<Map<String, Object>> relatedJoinPaths(List<Map<String, Object>> dbObjects, DbkgPhysicalSchemaCatalog physical) {
        Set<String> objectNames = new LinkedHashSet<>();
        for (Map<String, Object> item : dbObjects) {
            objectNames.add(support.asText(item.get("objectName")));
        }
        if (objectNames.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : physical.joinPaths()) {
            if (!objectNames.contains(support.asText(row.get("leftObjectName")))
                    && !objectNames.contains(support.asText(row.get("rightObjectName")))) {
                continue;
            }
            merged.put(support.asText(row.get("joinName")), new LinkedHashMap<>(row));
        }
        for (Map<String, Object> row : support.readEnabledRowsOptional(support.cfg().getDbJoinPathTable())) {
            String joinName = support.asText(row.get("join_name"));
            if (joinName.isBlank()) {
                continue;
            }
            Map<String, Object> item = merged.computeIfAbsent(joinName, ignored -> new LinkedHashMap<>());
            item.put("joinName", joinName);
            support.putIfPresent(item, "leftObjectName", row.get("left_object_name"));
            support.putIfPresent(item, "rightObjectName", row.get("right_object_name"));
            support.putIfPresent(item, "joinType", row.get("join_type"));
            support.putIfPresent(item, "joinSqlFragment", row.get("join_sql_fragment"));
            support.putIfPresent(item, "businessReason", row.get("business_reason"));
            support.putIfPresent(item, "metadataJson", row.get("metadata_json"));
            support.putIfPresent(item, "confidenceScore", row.get("confidence_score"));
            support.putIfPresent(item, "llmHint", row.get("llm_hint"));
        }
        return merged.values().stream()
                .filter(row -> objectNames.contains(support.asText(row.get("leftObjectName")))
                        || objectNames.contains(support.asText(row.get("rightObjectName"))))
                .limit(Math.max(1, support.cfg().getMaxResults()))
                .toList();
    }

    private Map<String, Map<String, Object>> overlayObjectsByName() {
        Map<String, Map<String, Object>> overlays = new LinkedHashMap<>();
        for (Map<String, Object> row : support.readEnabledRowsOptional(support.cfg().getDbObjectTable())) {
            String objectName = support.normalizeKey(support.asText(row.get("object_name")));
            if (!objectName.isBlank()) {
                overlays.put(objectName, row);
            }
        }
        return overlays;
    }

    private Map<String, Map<String, Object>> overlayColumnsByKey() {
        Map<String, Map<String, Object>> overlays = new LinkedHashMap<>();
        for (Map<String, Object> row : support.readEnabledRowsOptional(support.cfg().getDbColumnTable())) {
            String key = support.normalizeColumnKey(support.asText(row.get("object_name")), support.asText(row.get("column_name")));
            if (!key.isBlank()) {
                overlays.put(key, row);
            }
        }
        return overlays;
    }

    private void applyObjectOverlay(Map<String, Object> target, Map<String, Object> overlay) {
        if (overlay == null) {
            return;
        }
        support.putIfPresent(target, "systemCode", overlay.get("system_code"));
        support.putIfPresent(target, "entityCode", overlay.get("entity_code"));
        support.putIfPresent(target, "description", overlay.get("description"));
        support.putIfPresent(target, "accessMode", overlay.get("access_mode"));
        support.putIfPresent(target, "metadataJson", overlay.get("metadata_json"));
        support.putIfPresent(target, "llmHint", overlay.get("llm_hint"));
    }

    private void applyColumnOverlay(Map<String, Object> target, Map<String, Object> overlay) {
        if (overlay == null) {
            return;
        }
        support.putIfPresent(target, "semanticName", overlay.get("semantic_name"));
        support.putIfPresent(target, "description", overlay.get("description"));
        support.putIfPresent(target, "synonyms", overlay.get("synonyms"));
        support.putIfPresent(target, "keyType", overlay.get("key_type"));
        support.putIfPresent(target, "metadataJson", overlay.get("metadata_json"));
        support.putIfPresent(target, "llmHint", overlay.get("llm_hint"));
        if (overlay.containsKey("nullable_flag")) {
            target.put("nullableFlag", overlay.get("nullable_flag"));
        }
        support.putIfPresent(target, "dataType", overlay.get("data_type"));
    }

    private Set<String> readPrimaryKeys(DatabaseMetaData metaData, String catalog, String schemaName, String tableName) {
        Set<String> keys = new HashSet<>();
        try (ResultSet pk = metaData.getPrimaryKeys(catalog, schemaName, tableName)) {
            while (pk.next()) {
                String column = pk.getString("COLUMN_NAME");
                if (column != null) {
                    keys.add(column.toLowerCase());
                }
            }
        } catch (Exception ignored) {
        }
        return keys;
    }

    private boolean containsObject(List<Map<String, Object>> objects, String objectName) {
        String normalized = support.normalizeKey(objectName);
        for (Map<String, Object> object : objects) {
            if (normalized.equals(support.normalizeKey(support.asText(object.get("objectName"))))) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> deduplicateColumns(List<Map<String, Object>> columns) {
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> column : columns) {
            deduped.put(
                    support.normalizeColumnKey(support.asText(column.get("objectName")), support.asText(column.get("columnName"))),
                    column);
        }
        return new ArrayList<>(deduped.values());
    }

    private List<Map<String, Object>> deduplicateByKey(List<Map<String, Object>> rows, String keyName) {
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = support.asText(row.get(keyName));
            if (!key.isBlank()) {
                deduped.put(support.normalizeKey(key), row);
            }
        }
        return new ArrayList<>(deduped.values());
    }
}
