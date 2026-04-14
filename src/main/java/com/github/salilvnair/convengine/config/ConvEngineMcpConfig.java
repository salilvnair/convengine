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

    @Getter
    @Setter
    public static class Db {
        private Knowledge knowledge = new Knowledge();
        private Preflight preflight = new Preflight();

        @Getter
        @Setter
        public static class Knowledge {
            private boolean enabled = false;
            private String toolCode = "db.knowledge.graph";
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
        }

        @Getter
        @Setter
        public static class Preflight {
            /**
             * Enables framework-side SQL preflight validation/coercion before DB execution.
             */
            private boolean enabled = true;
            /**
             * If true, fail query execution when referenced tables/columns are unknown.
             */
            private boolean strictSchema = true;
            /**
             * If true, attempt numeric coercion for bound params and simple SQL literals.
             */
            private boolean coerceNumeric = true;
            /**
             * Validate SQL against runtime semantic tables before physical DB metadata checks.
             */
            private Semantic semantic = new Semantic();
            /**
             * Optional domain mappings by column key.
             * Key can be "table.column" or just "column" (lowercase recommended).
             * Example:
             * {
             *   "zp_request_trans_data.status": {"FAILED":"120","ERROR":"130"},
             *   "status": {"FAILED":"120"}
             * }
             */
            private Map<String, Map<String, String>> valueMappings = new LinkedHashMap<>();
            /**
             * If true, failed SQL execution can be retried with LLM-based SQL correction.
             */
            private boolean sqlAutoRepairEnabled = false;
            /**
             * Maximum correction retries after initial execution attempt.
             */
            private int sqlAutoRepairMaxRetries = 3;

            @Getter
            @Setter
            public static class Semantic {
                private boolean enabled = false;
                /**
                 * If true, fail when table/column is not represented in semantic mapping rows.
                 */
                private boolean strictMapping = false;
                /**
                 * If true, fail when join pair is missing in semantic join hints.
                 */
                private boolean strictJoinPath = false;

                private String mappingTable = "ce_semantic_mapping";
                private String mappingTableColumn = "mapped_table";
                private String mappingColumnColumn = "mapped_column";

                private String joinHintTable = "ce_semantic_join_hint";
                private String joinLeftTableColumn = "left_table";
                private String joinRightTableColumn = "right_table";

                private String sourceTableCatalogTable = "ce_semantic_source_table";
                private String sourceTableNameColumn = "table_name";

                private String sourceColumnCatalogTable = "ce_semantic_source_column";
                private String sourceColumnTableNameColumn = "table_name";
                private String sourceColumnNameColumn = "column_name";
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
