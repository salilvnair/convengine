package com.github.salilvnair.convengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "convengine.mcp")
@Getter
@Setter
public class ConvEngineMcpConfig {

    private Db db = new Db();
    private HttpApi httpApi = new HttpApi();
    private Guardrail guardrail = new Guardrail();
    private int toolMaxLoops = 5;
    private long toolCallDelayMs = 0L;
    private int toolCallDelayAfterCalls = 4;
    private long toolCallDelayAfterMs = 2000L;
    private int plannerMaxObservationChars = 4000;

    @Getter
    @Setter
    public static class Db {
        private Query query = new Query();
        private Knowledge semanticCatalog = new Knowledge();
        private KnowledgeGraph knowledgeGraph = new KnowledgeGraph();
        private Semantic semantic = new Semantic();
        private String sqlGuardrailTable = "ce_mcp_sql_guardrail";
        /**
         * Optional schema-introspection allow-list.
         * Supports exact table names (e.g. ce_config) and wildcard patterns (e.g. ce_*).
         * When set, only matching tables are introspected.
         */
        private List<String> introspectTables = new ArrayList<>();

        public Knowledge semanticCatalogConfig() {
            return semanticCatalog == null ? new Knowledge() : semanticCatalog;
        }

        @Getter
        @Setter
        public static class Knowledge {
            private boolean enabled = false;
            private boolean knowledgeCapsule = true;
            private boolean schemaKnowledge = true;
            private boolean queryKnowledge = true;
            private VectorSearch vectorSearch = new VectorSearch();
            private int maxResults = 5;
            private int scanLimit = 5000;
            private double minScore = 0.15d;

            private String queryCatalogTable = "ce_mcp_query_knowledge";
            private String queryTextColumn = "query_text";
            private String queryDescriptionColumn = "description";
            private String preparedSqlColumn = "prepared_sql";
            private String tagsColumn = "tags";
            private String apiHintsColumn = "api_hints";

            private String schemaCatalogTable = "ce_mcp_schema_knowledge";
            private String schemaTableNameColumn = "table_name";
            private String schemaColumnNameColumn = "column_name";
            private String schemaDescriptionColumn = "description";
            private String schemaTagsColumn = "tags";
            private String schemaValidValuesColumn = "valid_values";

            @Getter
            @Setter
            public static class VectorSearch {
                private boolean enabled = false;
                private int maxResults = 10;
                private String vectorColumn = "vector";
            }
        }

        @Getter
        @Setter
        public static class KnowledgeGraph {
            private boolean enabled = false;
            private int maxResults = 5;
            private int scanLimit = 5000;
            private boolean schemaIntrospectionEnabled = true;
            private int schemaObjectLimit = 200;
            private List<String> includedSchemas = new ArrayList<>(List.of("public"));
            private List<String> excludedSchemas = new ArrayList<>(List.of("information_schema", "pg_catalog"));
            private boolean sqlRefinementEnabled = false;
            private String sqlDialect = "postgres";
            private String caseResolveToolCode = "dbkg.case.resolve";
            private String knowledgeLookupToolCode = "dbkg.knowledge.lookup";
            private String investigatePlanToolCode = "dbkg.investigate.plan";
            private String investigateExecuteToolCode = "dbkg.investigate.execute";
            private String playbookValidateToolCode = "dbkg.playbook.validate";

            private String caseTypeTable = "ce_mcp_case_type";
            private String caseSignalTable = "ce_mcp_case_signal";
            private String playbookTable = "ce_mcp_playbook";
            private String playbookSignalTable = "ce_mcp_playbook_signal";
            private String domainEntityTable = "ce_mcp_domain_entity";
            private String domainRelationTable = "ce_mcp_domain_relation";
            private String systemNodeTable = "ce_mcp_system_node";
            private String systemRelationTable = "ce_mcp_system_relation";
            private String apiFlowTable = "ce_mcp_api_flow";
            private String dbObjectTable = "ce_mcp_db_object";
            private String dbColumnTable = "ce_mcp_db_column";
            private String dbJoinPathTable = "ce_mcp_db_join_path";
            private String statusDictionaryTable = "ce_mcp_status_dictionary";
            private String idLineageTable = "ce_mcp_id_lineage";
            private String queryTemplateTable = "ce_mcp_query_template";
            private String queryParamRuleTable = "ce_mcp_query_param_rule";
            private String playbookStepTable = "ce_mcp_playbook_step";
            private String playbookTransitionTable = "ce_mcp_playbook_transition";
            private String outcomeRuleTable = "ce_mcp_outcome_rule";
            private String executorTemplateTable = "ce_mcp_executor_template";
        }

        @Getter
        @Setter
        public static class Query {
            /**
             * Supported values:
             * semantic-catalog | knowledge-graph | semantic
             */
            private String mode = "semantic-catalog";
        }

        @Getter
        @Setter
        public static class Semantic {
            private boolean enabled = false;
            private String toolCode = "db.semantic.query";
            /**
             * Controls how db.semantic.query compiles SQL.
             * Supported values: llm | deterministic
             */
            private String queryMode = "llm";
            /**
             * When true, db.semantic.query v2 path (resolvedPlan input) fails fast
             * on incomplete/invalid resolved plans instead of attempting fallback.
             */
            private boolean strictMode = false;
            private String modelPath = "classpath:/mcp/semantic-layer.yml";
            private int defaultLimit = 100;
            private int maxLimit = 500;
            private String sqlDialect = "postgres";
            private String timezone = "UTC";
            private int maxJoinHops = 6;
            private int maxTables = 10;

            private Retrieval retrieval = new Retrieval();
            private Vector vector = new Vector();
            private Graph graph = new Graph();
            private Clarification clarification = new Clarification();

            @Getter
            @Setter
            public static class Retrieval {
                private int maxEntities = 3;
                private int maxTables = 6;
                private double minEntityScore = 0.35d;
                private double minTableScore = 0.30d;
                private double synonymWeight = 0.40d;
                private double fieldWeight = 0.25d;
                private double idPatternWeight = 0.20d;
                private double lexicalWeight = 0.15d;
                private double deterministicBlendWeight = 0.70d;
                private double vectorBlendWeight = 0.30d;
            }

            @Getter
            @Setter
            public static class Vector {
                private boolean enabled = false;
                /**
                 * Controls whether USER_QUERY embeddings from thumbs feedback
                 * are indexed and used as retrieval-time boost.
                 */
                private boolean feedbackEnabled = true;
                /**
                 * Postgres table containing semantic embedding rows.
                 */
                private String table = "ce_mcp_semantic_embedding";
                private String namespaceColumn = "namespace";
                private String targetTypeColumn = "target_type";
                private String targetNameColumn = "target_name";
                private String embeddingColumn = "embedding";
                private String metadataColumn = "metadata_json";
                private int maxResults = 20;
            }

            @Getter
            @Setter
            public static class Graph {
                /**
                 * Adapter key for graph engine implementation.
                 * Supported default: jgrapht
                 */
                private String adapter = "jgrapht";
            }

            @Getter
            @Setter
            public static class Clarification {
                /**
                 * Enables clarification loop for semantic query ambiguity.
                 */
                private boolean enabled = true;
                /**
                 * Ask follow-up if calculated confidence is below this threshold.
                 */
                private double confidenceThreshold = 0.80d;
                /**
                 * Weight of retrieval confidence in overall confidence score.
                 */
                private double retrievalWeight = 0.60d;
                /**
                 * Weight of join-path confidence in overall confidence score.
                 */
                private double joinWeight = 0.40d;
                /**
                 * If top-2 entity candidates are too close, ask clarification.
                 */
                private double minTopEntityGap = 0.12d;
                /**
                 * Number of options to include in clarification question.
                 */
                private int maxEntityOptions = 2;
            }

        }
    }

    @Getter
    @Setter
    public static class HttpApi {
        private Policy defaults = new Policy();

        @Getter
        @Setter
        public static class Policy {
            private int connectTimeoutMs = 2000;
            private int readTimeoutMs = 5000;
            private int maxAttempts = 2;
            private long initialBackoffMs = 200L;
            private long maxBackoffMs = 2000L;
            private double backoffMultiplier = 2.0d;
            private boolean circuitBreakerEnabled = true;
            private int circuitFailureThreshold = 5;
            private long circuitOpenMs = 30000L;
            private List<Integer> retryStatusCodes = new ArrayList<>(List.of(429, 502, 503, 504));
            private boolean retryOnIOException = true;
        }
    }

    @Getter
    @Setter
    public static class Guardrail {
        /**
         * Enables post-planner, pre-tool guard checks.
         */
        private boolean enabled = false;
        /**
         * If true and no rule exists for current tool, block by default.
         * If false and no rule exists, allow.
         */
        private boolean failClosed = false;
        /**
         * Key: current tool code (or __START__ for first tool)
         * Value: allowed next tool codes.
         */
        private Map<String, List<String>> allowedNextByCurrentTool = new LinkedHashMap<>();
    }
}
