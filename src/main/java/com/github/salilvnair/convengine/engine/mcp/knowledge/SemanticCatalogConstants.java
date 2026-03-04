package com.github.salilvnair.convengine.engine.mcp.knowledge;

import java.util.List;
import java.util.Set;

public final class SemanticCatalogConstants {

    private SemanticCatalogConstants() {
    }

    public static final String SOURCE_TYPE_QUERY = "query";
    public static final String SOURCE_TYPE_SCHEMA = "schema";

    public static final String KEY_SCORE = "score";
    public static final String KEY_QUESTION = "question";
    public static final String KEY_QUERY_KNOWLEDGE = "queryKnowledge";
    public static final String KEY_SCHEMA_KNOWLEDGE = "schemaKnowledge";
    public static final String KEY_INSIGHTS = "insights";
    public static final String KEY_SUGGESTED_PREPARED_QUERIES = "suggestedPreparedQueries";
    public static final String KEY_SUGGESTED_TABLES = "suggestedTables";
    public static final String KEY_DBKG_CAPSULE = "dbkgCapsule";
    public static final String KEY_FEATURES = "features";
    public static final String KEY_KNOWLEDGE_CAPSULE = "knowledgeCapsule";
    public static final String KEY_QUERY_KNOWLEDGE_FLAG = "queryKnowledge";
    public static final String KEY_SCHEMA_KNOWLEDGE_FLAG = "schemaKnowledge";
    public static final String KEY_VECTOR_SEARCH = "vectorSearch";

    public static final String KEY_TABLE_NAME = "tableName";
    public static final String KEY_COLUMN_NAME = "columnName";
    public static final String KEY_DESCRIPTION = "description";
    public static final String KEY_TAGS = "tags";
    public static final String KEY_VALID_VALUES = "validValues";
    public static final String KEY_QUERY_TEXT = "queryText";
    public static final String KEY_PREPARED_SQL = "preparedSql";
    public static final String KEY_API_HINTS = "apiHints";

    public static final String KEY_VERSION = "version";
    public static final String KEY_SOURCE = "source";
    public static final String KEY_SOURCE_COVERAGE = "sourceCoverage";
    public static final String KEY_SQL_GRAPH = "sqlGraph";
    public static final String KEY_SEMANTIC_GRAPH = "semanticGraph";
    public static final String KEY_PLANNER_RUNTIME = "plannerRuntime";
    public static final String KEY_OBJECTS = "objects";
    public static final String KEY_COLUMNS_BY_OBJECT = "columnsByObject";
    public static final String KEY_VALID_VALUES_BY_OBJECT = "validValuesByObject";
    public static final String KEY_SCHEMA_KNOWLEDGE_BY_OBJECT = "schemaKnowledgeByObject";
    public static final String KEY_QUERY_TEMPLATES = "queryTemplates";
    public static final String KEY_JOIN_PATHS = "joinPaths";
    public static final String KEY_STATUS_DICTIONARY = "statusDictionary";
    public static final String KEY_LINEAGE = "lineage";
    public static final String KEY_ENTITIES = "entities";
    public static final String KEY_CASES = "cases";
    public static final String KEY_PLAYBOOKS = "playbooks";
    public static final String KEY_SYSTEMS = "systems";
    public static final String KEY_API_FLOWS = "apiFlows";
    public static final String KEY_HINTS = "hints";

    public static final String KEY_CE_MCP_QUERY_KNOWLEDGE = "ce_mcp_query_knowledge";
    public static final String KEY_CE_MCP_SCHEMA_KNOWLEDGE = "ce_mcp_schema_knowledge";

    public static final String VERSION_SEMANTIC_CATALOG_CAPSULE_V2 = "semantic-catalog-capsule-v2";
    public static final String SOURCE_DB_SEMANTIC_CATALOG = "db.semantic.catalog";
    public static final String HINT_SEMANTIC_CATALOG_CAPSULE = "semantic-catalog capsule";

    public static final String KEY_VECTOR_SCORE_ALT = "_vector_score";
    public static final String KEY_VECTOR_SCORE_CAMEL = "vectorScore";

    public static final String MESSAGE_UNSAFE_DB_IDENTIFIER_PREFIX = "Unsafe or blank DB knowledge identifier: ";

    public static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "for", "to", "in", "on", "with", "by",
            "is", "are", "was", "were", "be", "as", "at", "from", "it", "that", "this", "then",
            "how", "what", "when", "why", "who", "where", "show", "find", "get", "give", "tell");

    public static final List<String> EMPTY_HINTS = List.of(HINT_SEMANTIC_CATALOG_CAPSULE);
}
