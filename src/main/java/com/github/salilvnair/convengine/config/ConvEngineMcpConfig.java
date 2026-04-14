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
