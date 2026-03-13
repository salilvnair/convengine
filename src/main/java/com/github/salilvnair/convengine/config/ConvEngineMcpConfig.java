package com.github.salilvnair.convengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
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
        private Semantic semantic = new Semantic();
        private String sqlGuardrailTable = "";
        /**
         * Optional schema-introspection allow-list.
         * Supports exact table names (e.g. ce_config) and wildcard patterns (e.g. ce_*).
         * When set, only matching tables are introspected.
         */
        private List<String> introspectTables = new ArrayList<>();

        @Getter
        @Setter
        public static class Query {
            /**
             * Supported values:
             * semantic
             */
            private String mode = "semantic";
        }

        @Getter
        @Setter
        public static class Semantic {
            private boolean enabled = false;
            private String toolCode = "db.semantic.query";
            private String modelPath = "classpath:/mcp/semantic-layer.yml";
            private int defaultLimit = 100;
            private int maxLimit = 500;
            private String sqlDialect = "postgres";
            private String timezone = ZoneId.systemDefault().getId();
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
                private String table = "ce_semantic_concept_embedding";
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
