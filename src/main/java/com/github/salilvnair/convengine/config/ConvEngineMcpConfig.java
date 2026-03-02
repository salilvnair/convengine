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
        private Knowledge knowledge = new Knowledge();
        private KnowledgeGraph knowledgeGraph = new KnowledgeGraph();
        private String sqlGuardrailTable = "ce_mcp_sql_guardrail";

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
