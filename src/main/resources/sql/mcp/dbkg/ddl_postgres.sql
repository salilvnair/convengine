-- ConvEngine MCP DB Knowledge Graph package (Postgres)
-- Purpose:
--   Provide a fully DB-driven metadata model for:
--   1) business-case identification
--   2) playbook selection
--   3) graph-backed query planning
--   4) multi-step investigation execution
--   5) outcome diagnosis and explanation
--
-- Notes:
--   - This package does not create consumer transaction tables.
--   - This package stores only metadata, rules, graph mappings, and templates.
--   - Example business cases: SERVICE_DISCONNECT, INVENTORY_MISMATCH.
--   - Example playbooks: ASR_ASSIGN_REJECT_AUDIT, ASO_SUBMITTED_NO_ZPDISCONNECTID_24H.

-- ---------------------------------------------------------------------------
-- 1) Case identification metadata
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_case_type
-- Purpose:
--   Stores coarse-grained business cases that MCP can classify before selecting
--   a specific investigation playbook.
-- Examples:
--   - SERVICE_DISCONNECT
--   - BILLING_RECONCILIATION
CREATE TABLE IF NOT EXISTS ce_mcp_case_type (
                                                case_code VARCHAR(100) PRIMARY KEY,
    case_name VARCHAR(200) NOT NULL,
    description TEXT,
    intent_code VARCHAR(100) NOT NULL DEFAULT 'ANY',
    state_code VARCHAR(100) NOT NULL DEFAULT 'ANY',
    priority INTEGER NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ce_mcp_case_type_case_code_not_blank CHECK (BTRIM(case_code) <> ''),
    CONSTRAINT ce_mcp_case_type_intent_not_blank CHECK (BTRIM(intent_code) <> ''),
    CONSTRAINT ce_mcp_case_type_state_not_blank CHECK (BTRIM(state_code) <> '')
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_case_type_lookup
    ON ce_mcp_case_type (enabled, intent_code, state_code, priority, case_code);

-- Table: ce_mcp_case_signal
-- Purpose:
--   Stores scoreable signals that map free-form user language to a case type.
-- Examples:
--   - KEYWORD + "disconnect" => SERVICE_DISCONNECT
--   - SYSTEM_NAME + "BillBank" => BILLING_RECONCILIATION
CREATE TABLE IF NOT EXISTS ce_mcp_case_signal (
                                                  signal_id BIGSERIAL PRIMARY KEY,
                                                  case_code VARCHAR(100) NOT NULL REFERENCES ce_mcp_case_type(case_code) ON DELETE CASCADE,
    signal_type VARCHAR(50) NOT NULL,
    match_operator VARCHAR(30) NOT NULL DEFAULT 'CONTAINS',
    match_value VARCHAR(1000) NOT NULL,
    weight NUMERIC(8,2) NOT NULL DEFAULT 1.00,
    required_flag BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description TEXT
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_case_signal_lookup
    ON ce_mcp_case_signal (case_code, enabled, signal_type, match_operator);

-- ---------------------------------------------------------------------------
-- 2) Playbook resolution metadata
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_playbook
-- Purpose:
--   Stores investigation playbooks under each business case. A case may have
--   many playbooks, each optimized for a different symptom.
-- Examples:
--   - ASR_ASSIGN_REJECT_AUDIT
--   - ZPOS_BILLBANK_GAP
CREATE TABLE IF NOT EXISTS ce_mcp_playbook (
                                               playbook_code VARCHAR(120) PRIMARY KEY,
    case_code VARCHAR(100) NOT NULL REFERENCES ce_mcp_case_type(case_code) ON DELETE CASCADE,
    playbook_name VARCHAR(200) NOT NULL,
    description TEXT,
    entry_strategy VARCHAR(30) NOT NULL DEFAULT 'TOP_SCORE',
    priority INTEGER NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ce_mcp_playbook_code_not_blank CHECK (BTRIM(playbook_code) <> '')
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_playbook_lookup
    ON ce_mcp_playbook (case_code, enabled, priority, playbook_code);

-- Table: ce_mcp_playbook_signal
-- Purpose:
--   Stores finer-grained symptom signals used to pick the best playbook once a
--   case has already been identified.
-- Examples:
--   - ASR + assigned + rejected + 24 hours
--   - submitted + zpDisconnectId + 24hr
CREATE TABLE IF NOT EXISTS ce_mcp_playbook_signal (
                                                      playbook_signal_id BIGSERIAL PRIMARY KEY,
                                                      playbook_code VARCHAR(120) NOT NULL REFERENCES ce_mcp_playbook(playbook_code) ON DELETE CASCADE,
    signal_type VARCHAR(50) NOT NULL,
    match_operator VARCHAR(30) NOT NULL DEFAULT 'CONTAINS',
    match_value VARCHAR(1000) NOT NULL,
    weight NUMERIC(8,2) NOT NULL DEFAULT 1.00,
    required_flag BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description TEXT
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_playbook_signal_lookup
    ON ce_mcp_playbook_signal (playbook_code, enabled, signal_type, match_operator);

-- ---------------------------------------------------------------------------
-- 3) Business and system knowledge graph
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_domain_entity
-- Purpose:
--   Canonical business nouns, responsibilities, and important IDs that users
--   talk about in natural language.
-- Examples:
--   - CUSTOMER
--   - DISCONNECT_ORDER
CREATE TABLE IF NOT EXISTS ce_mcp_domain_entity (
                                                    entity_code VARCHAR(100) PRIMARY KEY,
    entity_name VARCHAR(200) NOT NULL,
    description TEXT,
    synonyms TEXT,
    criticality VARCHAR(30) NOT NULL DEFAULT 'MEDIUM',
    metadata_json TEXT,
    llm_hint TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_domain_entity_lookup
    ON ce_mcp_domain_entity (enabled, criticality, entity_code);

-- Table: ce_mcp_domain_relation
-- Purpose:
--   Business graph edges between domain entities.
-- Examples:
--   - CUSTOMER "OWNS" ACCOUNT
--   - DISCONNECT_REQUEST "GENERATES" DISCONNECT_ORDER
CREATE TABLE IF NOT EXISTS ce_mcp_domain_relation (
                                                      relation_id BIGSERIAL PRIMARY KEY,
                                                      from_entity_code VARCHAR(100) NOT NULL REFERENCES ce_mcp_domain_entity(entity_code) ON DELETE CASCADE,
    relation_type VARCHAR(60) NOT NULL,
    to_entity_code VARCHAR(100) NOT NULL REFERENCES ce_mcp_domain_entity(entity_code) ON DELETE CASCADE,
    cardinality VARCHAR(30) NOT NULL DEFAULT 'MANY_TO_ONE',
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_domain_relation_lookup
    ON ce_mcp_domain_relation (from_entity_code, to_entity_code, enabled);

-- Table: ce_mcp_system_node
-- Purpose:
--   Stores major systems/services in the operational flow.
-- Examples:
--   - ZAPPER_CENTRAL
--   - ZAPPER_BILLBANK
CREATE TABLE IF NOT EXISTS ce_mcp_system_node (
                                                  system_code VARCHAR(100) PRIMARY KEY,
    system_name VARCHAR(200) NOT NULL,
    system_type VARCHAR(50) NOT NULL,
    description TEXT,
    metadata_json TEXT,
    llm_hint TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_system_node_lookup
    ON ce_mcp_system_node (enabled, system_type, system_code);

-- Table: ce_mcp_api_flow
-- Purpose:
--   Declarative API-call catalog for a use case. Each row can describe the API,
--   dependent systems, involved tables/columns, and any LLM hints as JSON.
-- Examples:
--   - ZAPPER_CENTRAL nightly validation flow
--   - Disconnect submit call chain into order service
CREATE TABLE IF NOT EXISTS ce_mcp_api_flow (
                                               api_flow_id BIGSERIAL PRIMARY KEY,
                                               api_code VARCHAR(120) NOT NULL UNIQUE,
    api_name VARCHAR(200) NOT NULL,
    system_code VARCHAR(100) REFERENCES ce_mcp_system_node(system_code) ON DELETE SET NULL,
    flow_type VARCHAR(60) NOT NULL DEFAULT 'SYNC',
    description TEXT,
    metadata_json TEXT,
    llm_hint TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_api_flow_lookup
    ON ce_mcp_api_flow (enabled, system_code, flow_type, api_code);

-- Table: ce_mcp_system_relation
-- Purpose:
--   Stores system-to-system flow edges to explain routing and downstream calls.
-- Examples:
--   - ZAPPER_UI "CALLS" ZAPPER_CENTRAL
--   - ZAPPER_CENTRAL "CALLS" ZAPPER_ORDER_SERVICE
CREATE TABLE IF NOT EXISTS ce_mcp_system_relation (
                                                      system_relation_id BIGSERIAL PRIMARY KEY,
                                                      from_system_code VARCHAR(100) NOT NULL REFERENCES ce_mcp_system_node(system_code) ON DELETE CASCADE,
    relation_type VARCHAR(60) NOT NULL,
    to_system_code VARCHAR(100) NOT NULL REFERENCES ce_mcp_system_node(system_code) ON DELETE CASCADE,
    sequence_no INTEGER NOT NULL DEFAULT 1,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_system_relation_lookup
    ON ce_mcp_system_relation (from_system_code, to_system_code, enabled, sequence_no);

-- ---------------------------------------------------------------------------
-- 4) Database object knowledge graph
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_db_object
-- Purpose:
--   Optional semantic overrides for auto-discovered DB objects. The runtime now
--   reads physical tables/views from JDBC metadata first, then overlays any
--   matching rows from this table.
-- Examples:
--   - Map zp_request to DISCONNECT_REQUEST
--   - Attach a description to zp_ui_data_history
CREATE TABLE IF NOT EXISTS ce_mcp_db_object (
                                                object_id BIGSERIAL PRIMARY KEY,
                                                object_name VARCHAR(255) NOT NULL UNIQUE,
    object_type VARCHAR(40) NOT NULL,
    system_code VARCHAR(100) REFERENCES ce_mcp_system_node(system_code) ON DELETE SET NULL,
    entity_code VARCHAR(100) REFERENCES ce_mcp_domain_entity(entity_code) ON DELETE SET NULL,
    access_mode VARCHAR(30) NOT NULL DEFAULT 'READ_ONLY',
    description TEXT,
    metadata_json TEXT,
    llm_hint TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_db_object_lookup
    ON ce_mcp_db_object (enabled, object_type, system_code, entity_code);

-- Table: ce_mcp_db_column
-- Purpose:
--   Optional semantic overrides for auto-discovered columns. Users should not
--   need to register every column manually; only enrich columns when business
--   meaning is missing from the physical schema.
-- Examples:
--   - Annotate zp_ui_data.zp_action_id with semantic name action_code
--   - Add synonyms for a confusing legacy column name
CREATE TABLE IF NOT EXISTS ce_mcp_db_column (
                                                column_id BIGSERIAL PRIMARY KEY,
                                                object_name VARCHAR(255) NOT NULL,
    column_name VARCHAR(255) NOT NULL,
    semantic_name VARCHAR(255),
    data_type VARCHAR(80),
    nullable_flag BOOLEAN NOT NULL DEFAULT TRUE,
    key_type VARCHAR(30) NOT NULL DEFAULT 'NONE',
    synonyms TEXT,
    description TEXT,
    metadata_json TEXT,
    llm_hint TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (object_name, column_name)
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_db_column_lookup
    ON ce_mcp_db_column (object_name, enabled, key_type, column_name);

-- Table: ce_mcp_db_join_path
-- Purpose:
--   Optional preferred join overrides. Runtime auto-discovers FK joins from
--   JDBC metadata first, then overlays any curated joins from this table.
-- Examples:
--   - Override a join with business_reason explaining why it is preferred
--   - Add a curated join when no FK exists in the database
CREATE TABLE IF NOT EXISTS ce_mcp_db_join_path (
                                                   join_path_id BIGSERIAL PRIMARY KEY,
                                                   join_name VARCHAR(150) NOT NULL UNIQUE,
    left_object_name VARCHAR(255) NOT NULL,
    right_object_name VARCHAR(255) NOT NULL,
    join_type VARCHAR(30) NOT NULL DEFAULT 'INNER',
    join_sql_fragment TEXT NOT NULL,
    business_reason TEXT,
    metadata_json TEXT,
    llm_hint TEXT,
    confidence_score NUMERIC(5,2) NOT NULL DEFAULT 1.00,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_db_join_path_lookup
    ON ce_mcp_db_join_path (left_object_name, right_object_name, enabled);

-- Table: ce_mcp_status_dictionary
-- Purpose:
--   Humanizes numeric/string statuses used in DB rows and APIs.
-- Examples:
--   - ZP_UI_ACTION.zp_action_id 200 = ASSIGNED
--   - ZP_UI_ACTION.zp_action_id 400 = REJECTED
CREATE TABLE IF NOT EXISTS ce_mcp_status_dictionary (
                                                        status_id BIGSERIAL PRIMARY KEY,
                                                        dictionary_name VARCHAR(100) NOT NULL,
    field_name VARCHAR(255) NOT NULL,
    code_value VARCHAR(100) NOT NULL,
    code_label VARCHAR(200) NOT NULL,
    business_meaning TEXT,
    synonyms TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (dictionary_name, field_name, code_value)
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_status_dictionary_lookup
    ON ce_mcp_status_dictionary (dictionary_name, field_name, enabled, code_value);

-- Table: ce_mcp_id_lineage
-- Purpose:
--   Stores ID propagation knowledge across systems and objects.
-- Examples:
--   - zp_request_id flows from zp_request to zp_ui_data
--   - zpDisconnectId flows from ZapperOS to BillBank
CREATE TABLE IF NOT EXISTS ce_mcp_id_lineage (
                                                 lineage_id BIGSERIAL PRIMARY KEY,
                                                 lineage_code VARCHAR(150) NOT NULL UNIQUE,
    source_system_code VARCHAR(100) REFERENCES ce_mcp_system_node(system_code) ON DELETE SET NULL,
    source_object_name VARCHAR(255),
    source_column_name VARCHAR(255) NOT NULL,
    target_system_code VARCHAR(100) REFERENCES ce_mcp_system_node(system_code) ON DELETE SET NULL,
    target_object_name VARCHAR(255),
    target_column_name VARCHAR(255) NOT NULL,
    transform_rule TEXT,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_id_lineage_lookup
    ON ce_mcp_id_lineage (enabled, source_column_name, target_column_name);

-- ---------------------------------------------------------------------------
-- 5) Generic executors and templates
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_executor_template
-- Purpose:
--   Registers generic step executors and their config contracts. Java remains
--   generic and looks up executors by code instead of hardcoding use cases.
-- Examples:
--   - SQL_TEMPLATE
--   - STATUS_LOOKUP
CREATE TABLE IF NOT EXISTS ce_mcp_executor_template (
                                                        executor_code VARCHAR(100) PRIMARY KEY,
    executor_type VARCHAR(40) NOT NULL,
    config_schema_json TEXT,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_executor_template_lookup
    ON ce_mcp_executor_template (enabled, executor_type, executor_code);

-- Table: ce_mcp_query_template
-- Purpose:
--   Stores approved SQL templates and their execution metadata.
-- Examples:
--   - ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS
--   - ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME
CREATE TABLE IF NOT EXISTS ce_mcp_query_template (
                                                     query_code VARCHAR(120) PRIMARY KEY,
    playbook_code VARCHAR(120) REFERENCES ce_mcp_playbook(playbook_code) ON DELETE SET NULL,
    executor_code VARCHAR(100) NOT NULL REFERENCES ce_mcp_executor_template(executor_code),
    purpose TEXT NOT NULL,
    sql_template TEXT NOT NULL,
    required_params TEXT,
    optional_params TEXT,
    result_contract TEXT,
    safety_class VARCHAR(30) NOT NULL DEFAULT 'READ_ONLY_STRICT',
    default_limit INTEGER NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_query_template_lookup
    ON ce_mcp_query_template (enabled, playbook_code, safety_class, query_code);

-- Table: ce_mcp_query_param_rule
-- Purpose:
--   Defines how each query parameter is derived without Java-specific logic.
-- Examples:
--   - from_action_id from status dictionary
--   - from_ts from derived 24h time window
CREATE TABLE IF NOT EXISTS ce_mcp_query_param_rule (
                                                       param_rule_id BIGSERIAL PRIMARY KEY,
                                                       query_code VARCHAR(120) NOT NULL REFERENCES ce_mcp_query_template(query_code) ON DELETE CASCADE,
    param_name VARCHAR(120) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_key VARCHAR(255),
    default_value TEXT,
    required_flag BOOLEAN NOT NULL DEFAULT TRUE,
    transform_rule TEXT,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (query_code, param_name)
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_query_param_rule_lookup
    ON ce_mcp_query_param_rule (query_code, enabled, source_type);

-- ---------------------------------------------------------------------------
-- 6) Playbook execution graph
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_playbook_step
-- Purpose:
--   Stores executable steps for each playbook. Steps point to executor codes
--   and template codes so Java can run them generically.
-- Examples:
--   - RESOLVE_TIME_WINDOW
--   - LOOKUP_ASSIGN_REJECT
CREATE TABLE IF NOT EXISTS ce_mcp_playbook_step (
                                                    step_id BIGSERIAL PRIMARY KEY,
                                                    playbook_code VARCHAR(120) NOT NULL REFERENCES ce_mcp_playbook(playbook_code) ON DELETE CASCADE,
    step_code VARCHAR(120) NOT NULL,
    step_type VARCHAR(50) NOT NULL,
    executor_code VARCHAR(100) NOT NULL REFERENCES ce_mcp_executor_template(executor_code),
    template_code VARCHAR(120),
    input_contract TEXT,
    output_contract TEXT,
    config_json TEXT,
    sequence_no INTEGER NOT NULL DEFAULT 1,
    halt_on_error BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (playbook_code, step_code)
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_playbook_step_lookup
    ON ce_mcp_playbook_step (playbook_code, enabled, sequence_no, step_code);

-- Table: ce_mcp_playbook_transition
-- Purpose:
--   Stores graph transitions between steps so flows can branch by outcome.
-- Examples:
--   - RESOLVE_TIME_WINDOW + SUCCESS => LOOKUP_ASSIGN_REJECT
--   - CHECK_DISCONNECT_PROPAGATION + GAP_FOUND => SUMMARIZE_FAILURE
CREATE TABLE IF NOT EXISTS ce_mcp_playbook_transition (
                                                          transition_id BIGSERIAL PRIMARY KEY,
                                                          playbook_code VARCHAR(120) NOT NULL REFERENCES ce_mcp_playbook(playbook_code) ON DELETE CASCADE,
    from_step_code VARCHAR(120) NOT NULL,
    outcome_code VARCHAR(80) NOT NULL,
    to_step_code VARCHAR(120),
    condition_expr TEXT,
    priority INTEGER NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_playbook_transition_lookup
    ON ce_mcp_playbook_transition (playbook_code, from_step_code, enabled, priority);

-- ---------------------------------------------------------------------------
-- 7) Final diagnosis rules
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_outcome_rule
-- Purpose:
--   Stores final classifications and explanation templates chosen after the
--   playbook has executed.
-- Examples:
--   - FOUND_TRANSITIONS
--   - BILLBANK_PROPAGATION_GAP
CREATE TABLE IF NOT EXISTS ce_mcp_outcome_rule (
                                                   outcome_rule_id BIGSERIAL PRIMARY KEY,
                                                   playbook_code VARCHAR(120) NOT NULL REFERENCES ce_mcp_playbook(playbook_code) ON DELETE CASCADE,
    outcome_code VARCHAR(80) NOT NULL,
    condition_expr TEXT NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'INFO',
    explanation_template TEXT NOT NULL,
    recommended_next_action TEXT,
    priority INTEGER NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_outcome_rule_lookup
    ON ce_mcp_outcome_rule (playbook_code, enabled, priority, outcome_code);


CREATE TABLE IF NOT EXISTS ce_mcp_sql_guardrail (
                                                    guardrail_id bigserial NOT NULL,
                                                    rule_type text NOT NULL,
                                                    match_value text NOT NULL,
                                                    enabled bool DEFAULT true NOT NULL,
                                                    description text NULL,
                                                    created_at timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT ce_mcp_sql_guardrail_pkey PRIMARY KEY (guardrail_id),
    CONSTRAINT ce_mcp_sql_guardrail_rule_type_not_blank CHECK (btrim(rule_type) <> ''),
    CONSTRAINT ce_mcp_sql_guardrail_match_value_not_blank CHECK (btrim(match_value) <> '')
    );
CREATE INDEX IF NOT EXISTS idx_ce_mcp_sql_guardrail_lookup
    ON ce_mcp_sql_guardrail USING btree (enabled, rule_type, match_value);
