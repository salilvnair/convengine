-- ConvEngine MCP DB Knowledge Graph package (SQLite)
-- Purpose:
--   SQLite-friendly copy of the DB-driven metadata model used by the MCP
--   investigation engine.
--
-- Notes:
--   - Stores metadata only, not consumer transaction data.
--   - Example cases: SERVICE_DISCONNECT, INVENTORY_MISMATCH.
--   - Example playbooks: ASR_ASSIGN_REJECT_AUDIT, ZPOS_BILLBANK_GAP.

-- ---------------------------------------------------------------------------
-- 1) Case identification metadata
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_case_type
-- Purpose: top-level business cases.
-- Examples: SERVICE_DISCONNECT, BILLING_RECONCILIATION.
CREATE TABLE IF NOT EXISTS ce_mcp_case_type (
  case_code TEXT PRIMARY KEY,
  case_name TEXT NOT NULL,
  description TEXT,
  intent_code TEXT NOT NULL DEFAULT 'ANY',
  state_code TEXT NOT NULL DEFAULT 'ANY',
  priority INTEGER NOT NULL DEFAULT 100,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_case_type_lookup
  ON ce_mcp_case_type (enabled, intent_code, state_code, priority, case_code);

-- Table: ce_mcp_case_signal
-- Purpose: scoreable user-language signals per case.
-- Examples: "disconnect", "BillBank".
CREATE TABLE IF NOT EXISTS ce_mcp_case_signal (
  signal_id INTEGER PRIMARY KEY AUTOINCREMENT,
  case_code TEXT NOT NULL,
  signal_type TEXT NOT NULL,
  match_operator TEXT NOT NULL DEFAULT 'CONTAINS',
  match_value TEXT NOT NULL,
  weight REAL NOT NULL DEFAULT 1.0,
  required_flag INTEGER NOT NULL DEFAULT 0,
  enabled INTEGER NOT NULL DEFAULT 1,
  description TEXT,
  FOREIGN KEY (case_code) REFERENCES ce_mcp_case_type(case_code) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_case_signal_lookup
  ON ce_mcp_case_signal (case_code, enabled, signal_type, match_operator);

-- ---------------------------------------------------------------------------
-- 2) Playbook resolution metadata
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_playbook
-- Purpose: investigation playbooks per case.
-- Examples: ASR_ASSIGN_REJECT_AUDIT, ZPOS_BILLBANK_GAP.
CREATE TABLE IF NOT EXISTS ce_mcp_playbook (
  playbook_code TEXT PRIMARY KEY,
  case_code TEXT NOT NULL,
  playbook_name TEXT NOT NULL,
  description TEXT,
  entry_strategy TEXT NOT NULL DEFAULT 'TOP_SCORE',
  priority INTEGER NOT NULL DEFAULT 100,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (case_code) REFERENCES ce_mcp_case_type(case_code) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_playbook_lookup
  ON ce_mcp_playbook (case_code, enabled, priority, playbook_code);

-- Table: ce_mcp_playbook_signal
-- Purpose: scoreable symptom signals per playbook.
-- Examples: "assigned", "rejected", "24 hours".
CREATE TABLE IF NOT EXISTS ce_mcp_playbook_signal (
  playbook_signal_id INTEGER PRIMARY KEY AUTOINCREMENT,
  playbook_code TEXT NOT NULL,
  signal_type TEXT NOT NULL,
  match_operator TEXT NOT NULL DEFAULT 'CONTAINS',
  match_value TEXT NOT NULL,
  weight REAL NOT NULL DEFAULT 1.0,
  required_flag INTEGER NOT NULL DEFAULT 0,
  enabled INTEGER NOT NULL DEFAULT 1,
  description TEXT,
  FOREIGN KEY (playbook_code) REFERENCES ce_mcp_playbook(playbook_code) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_playbook_signal_lookup
  ON ce_mcp_playbook_signal (playbook_code, enabled, signal_type, match_operator);

-- ---------------------------------------------------------------------------
-- 3) Business and system knowledge graph
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_domain_entity
-- Purpose: canonical business entities and IDs.
-- Examples: CUSTOMER, DISCONNECT_ORDER.
CREATE TABLE IF NOT EXISTS ce_mcp_domain_entity (
  entity_code TEXT PRIMARY KEY,
  entity_name TEXT NOT NULL,
  description TEXT,
  synonyms TEXT,
  criticality TEXT NOT NULL DEFAULT 'MEDIUM',
  metadata_json TEXT,
  llm_hint TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_domain_entity_lookup
  ON ce_mcp_domain_entity (enabled, criticality, entity_code);

-- Table: ce_mcp_domain_relation
-- Purpose: business graph edges.
-- Examples: CUSTOMER OWNS ACCOUNT, DISCONNECT_REQUEST GENERATES DISCONNECT_ORDER.
CREATE TABLE IF NOT EXISTS ce_mcp_domain_relation (
  relation_id INTEGER PRIMARY KEY AUTOINCREMENT,
  from_entity_code TEXT NOT NULL,
  relation_type TEXT NOT NULL,
  to_entity_code TEXT NOT NULL,
  cardinality TEXT NOT NULL DEFAULT 'MANY_TO_ONE',
  description TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  FOREIGN KEY (from_entity_code) REFERENCES ce_mcp_domain_entity(entity_code) ON DELETE CASCADE,
  FOREIGN KEY (to_entity_code) REFERENCES ce_mcp_domain_entity(entity_code) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_domain_relation_lookup
  ON ce_mcp_domain_relation (from_entity_code, to_entity_code, enabled);

-- Table: ce_mcp_system_node
-- Purpose: system/service nodes.
-- Examples: ZAPPER_CENTRAL, ZAPPER_BILLBANK.
CREATE TABLE IF NOT EXISTS ce_mcp_system_node (
  system_code TEXT PRIMARY KEY,
  system_name TEXT NOT NULL,
  system_type TEXT NOT NULL,
  description TEXT,
  metadata_json TEXT,
  llm_hint TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_system_node_lookup
  ON ce_mcp_system_node (enabled, system_type, system_code);

-- Table: ce_mcp_api_flow
-- Purpose: declarative API-call catalog with flow metadata and LLM hints.
-- Examples: nightly validation flow, disconnect submit flow.
CREATE TABLE IF NOT EXISTS ce_mcp_api_flow (
  api_flow_id INTEGER PRIMARY KEY AUTOINCREMENT,
  api_code TEXT NOT NULL UNIQUE,
  api_name TEXT NOT NULL,
  system_code TEXT,
  flow_type TEXT NOT NULL DEFAULT 'SYNC',
  description TEXT,
  metadata_json TEXT,
  llm_hint TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (system_code) REFERENCES ce_mcp_system_node(system_code) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_api_flow_lookup
  ON ce_mcp_api_flow (enabled, system_code, flow_type, api_code);

-- Table: ce_mcp_system_relation
-- Purpose: system-to-system flow graph.
-- Examples: ZAPPER_UI CALLS ZAPPER_CENTRAL, ZAPPER_CENTRAL CALLS ZAPPER_ORDER_SERVICE.
CREATE TABLE IF NOT EXISTS ce_mcp_system_relation (
  system_relation_id INTEGER PRIMARY KEY AUTOINCREMENT,
  from_system_code TEXT NOT NULL,
  relation_type TEXT NOT NULL,
  to_system_code TEXT NOT NULL,
  sequence_no INTEGER NOT NULL DEFAULT 1,
  description TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  FOREIGN KEY (from_system_code) REFERENCES ce_mcp_system_node(system_code) ON DELETE CASCADE,
  FOREIGN KEY (to_system_code) REFERENCES ce_mcp_system_node(system_code) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_system_relation_lookup
  ON ce_mcp_system_relation (from_system_code, to_system_code, enabled, sequence_no);

-- ---------------------------------------------------------------------------
-- 4) Database object knowledge graph
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_db_object
-- Purpose: optional semantic overrides for auto-discovered DB objects.
-- Examples: map zp_request to DISCONNECT_REQUEST, add a description to zp_ui_data_history.
CREATE TABLE IF NOT EXISTS ce_mcp_db_object (
  object_id INTEGER PRIMARY KEY AUTOINCREMENT,
  object_name TEXT NOT NULL UNIQUE,
  object_type TEXT NOT NULL,
  system_code TEXT,
  entity_code TEXT,
  access_mode TEXT NOT NULL DEFAULT 'READ_ONLY',
  description TEXT,
  metadata_json TEXT,
  llm_hint TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (system_code) REFERENCES ce_mcp_system_node(system_code) ON DELETE SET NULL,
  FOREIGN KEY (entity_code) REFERENCES ce_mcp_domain_entity(entity_code) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_db_object_lookup
  ON ce_mcp_db_object (enabled, object_type, system_code, entity_code);

-- Table: ce_mcp_db_column
-- Purpose: optional semantic overrides for auto-discovered columns.
-- Examples: annotate zp_ui_data.zp_action_id, add synonyms for legacy column names.
CREATE TABLE IF NOT EXISTS ce_mcp_db_column (
  column_id INTEGER PRIMARY KEY AUTOINCREMENT,
  object_name TEXT NOT NULL,
  column_name TEXT NOT NULL,
  semantic_name TEXT,
  data_type TEXT,
  nullable_flag INTEGER NOT NULL DEFAULT 1,
  key_type TEXT NOT NULL DEFAULT 'NONE',
  synonyms TEXT,
  description TEXT,
  metadata_json TEXT,
  llm_hint TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  UNIQUE (object_name, column_name)
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_db_column_lookup
  ON ce_mcp_db_column (object_name, enabled, key_type, column_name);

-- Table: ce_mcp_db_join_path
-- Purpose: optional preferred join overrides on top of auto-discovered FK joins.
-- Examples: override a preferred join, add a curated join where no FK exists.
CREATE TABLE IF NOT EXISTS ce_mcp_db_join_path (
  join_path_id INTEGER PRIMARY KEY AUTOINCREMENT,
  join_name TEXT NOT NULL UNIQUE,
  left_object_name TEXT NOT NULL,
  right_object_name TEXT NOT NULL,
  join_type TEXT NOT NULL DEFAULT 'INNER',
  join_sql_fragment TEXT NOT NULL,
  business_reason TEXT,
  metadata_json TEXT,
  llm_hint TEXT,
  confidence_score REAL NOT NULL DEFAULT 1.0,
  enabled INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_db_join_path_lookup
  ON ce_mcp_db_join_path (left_object_name, right_object_name, enabled);

-- Table: ce_mcp_status_dictionary
-- Purpose: humanizes status codes.
-- Examples: 200=ASSIGNED, 400=REJECTED.
CREATE TABLE IF NOT EXISTS ce_mcp_status_dictionary (
  status_id INTEGER PRIMARY KEY AUTOINCREMENT,
  dictionary_name TEXT NOT NULL,
  field_name TEXT NOT NULL,
  code_value TEXT NOT NULL,
  code_label TEXT NOT NULL,
  business_meaning TEXT,
  synonyms TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  UNIQUE (dictionary_name, field_name, code_value)
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_status_dictionary_lookup
  ON ce_mcp_status_dictionary (dictionary_name, field_name, enabled, code_value);

-- Table: ce_mcp_id_lineage
-- Purpose: ID propagation map across systems and objects.
-- Examples: zp_request_id to zp_ui_data, zpDisconnectId to BillBank.
CREATE TABLE IF NOT EXISTS ce_mcp_id_lineage (
  lineage_id INTEGER PRIMARY KEY AUTOINCREMENT,
  lineage_code TEXT NOT NULL UNIQUE,
  source_system_code TEXT,
  source_object_name TEXT,
  source_column_name TEXT NOT NULL,
  target_system_code TEXT,
  target_object_name TEXT,
  target_column_name TEXT NOT NULL,
  transform_rule TEXT,
  description TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  FOREIGN KEY (source_system_code) REFERENCES ce_mcp_system_node(system_code) ON DELETE SET NULL,
  FOREIGN KEY (target_system_code) REFERENCES ce_mcp_system_node(system_code) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_id_lineage_lookup
  ON ce_mcp_id_lineage (enabled, source_column_name, target_column_name);

-- ---------------------------------------------------------------------------
-- 5) Generic executors and templates
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_executor_template
-- Purpose: generic executor registry.
-- Examples: SQL_TEMPLATE, STATUS_LOOKUP.
CREATE TABLE IF NOT EXISTS ce_mcp_executor_template (
  executor_code TEXT PRIMARY KEY,
  executor_type TEXT NOT NULL,
  config_schema_json TEXT,
  description TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_executor_template_lookup
  ON ce_mcp_executor_template (enabled, executor_type, executor_code);

-- Table: ce_mcp_query_template
-- Purpose: approved SQL template catalog.
-- Examples: ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS, ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME.
CREATE TABLE IF NOT EXISTS ce_mcp_query_template (
  query_code TEXT PRIMARY KEY,
  playbook_code TEXT,
  executor_code TEXT NOT NULL,
  purpose TEXT NOT NULL,
  sql_template TEXT NOT NULL,
  required_params TEXT,
  optional_params TEXT,
  result_contract TEXT,
  safety_class TEXT NOT NULL DEFAULT 'READ_ONLY_STRICT',
  default_limit INTEGER NOT NULL DEFAULT 100,
  enabled INTEGER NOT NULL DEFAULT 1,
  description TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (playbook_code) REFERENCES ce_mcp_playbook(playbook_code) ON DELETE SET NULL,
  FOREIGN KEY (executor_code) REFERENCES ce_mcp_executor_template(executor_code)
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_query_template_lookup
  ON ce_mcp_query_template (enabled, playbook_code, safety_class, query_code);

-- Table: ce_mcp_query_param_rule
-- Purpose: declarative query parameter sourcing.
-- Examples: from_action_id from status dictionary, from_ts from time-window derivation.
CREATE TABLE IF NOT EXISTS ce_mcp_query_param_rule (
  param_rule_id INTEGER PRIMARY KEY AUTOINCREMENT,
  query_code TEXT NOT NULL,
  param_name TEXT NOT NULL,
  source_type TEXT NOT NULL,
  source_key TEXT,
  default_value TEXT,
  required_flag INTEGER NOT NULL DEFAULT 1,
  transform_rule TEXT,
  description TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  UNIQUE (query_code, param_name),
  FOREIGN KEY (query_code) REFERENCES ce_mcp_query_template(query_code) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_query_param_rule_lookup
  ON ce_mcp_query_param_rule (query_code, enabled, source_type);

-- ---------------------------------------------------------------------------
-- 6) Playbook execution graph
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_playbook_step
-- Purpose: executable steps in a playbook.
-- Examples: RESOLVE_TIME_WINDOW, LOOKUP_ASSIGN_REJECT.
CREATE TABLE IF NOT EXISTS ce_mcp_playbook_step (
  step_id INTEGER PRIMARY KEY AUTOINCREMENT,
  playbook_code TEXT NOT NULL,
  step_code TEXT NOT NULL,
  step_type TEXT NOT NULL,
  executor_code TEXT NOT NULL,
  template_code TEXT,
  input_contract TEXT,
  output_contract TEXT,
  config_json TEXT,
  sequence_no INTEGER NOT NULL DEFAULT 1,
  halt_on_error INTEGER NOT NULL DEFAULT 1,
  enabled INTEGER NOT NULL DEFAULT 1,
  UNIQUE (playbook_code, step_code),
  FOREIGN KEY (playbook_code) REFERENCES ce_mcp_playbook(playbook_code) ON DELETE CASCADE,
  FOREIGN KEY (executor_code) REFERENCES ce_mcp_executor_template(executor_code)
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_playbook_step_lookup
  ON ce_mcp_playbook_step (playbook_code, enabled, sequence_no, step_code);

-- Table: ce_mcp_playbook_transition
-- Purpose: step graph transitions.
-- Examples: SUCCESS -> next step, GAP_FOUND -> summarize failure.
CREATE TABLE IF NOT EXISTS ce_mcp_playbook_transition (
  transition_id INTEGER PRIMARY KEY AUTOINCREMENT,
  playbook_code TEXT NOT NULL,
  from_step_code TEXT NOT NULL,
  outcome_code TEXT NOT NULL,
  to_step_code TEXT,
  condition_expr TEXT,
  priority INTEGER NOT NULL DEFAULT 100,
  enabled INTEGER NOT NULL DEFAULT 1,
  FOREIGN KEY (playbook_code) REFERENCES ce_mcp_playbook(playbook_code) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_playbook_transition_lookup
  ON ce_mcp_playbook_transition (playbook_code, from_step_code, enabled, priority);

-- ---------------------------------------------------------------------------
-- 7) Final diagnosis rules
-- ---------------------------------------------------------------------------
-- Table: ce_mcp_outcome_rule
-- Purpose: final classifications and explanation templates.
-- Examples: FOUND_TRANSITIONS, BILLBANK_PROPAGATION_GAP.
CREATE TABLE IF NOT EXISTS ce_mcp_outcome_rule (
  outcome_rule_id INTEGER PRIMARY KEY AUTOINCREMENT,
  playbook_code TEXT NOT NULL,
  outcome_code TEXT NOT NULL,
  condition_expr TEXT NOT NULL,
  severity TEXT NOT NULL DEFAULT 'INFO',
  explanation_template TEXT NOT NULL,
  recommended_next_action TEXT,
  priority INTEGER NOT NULL DEFAULT 100,
  enabled INTEGER NOT NULL DEFAULT 1,
  FOREIGN KEY (playbook_code) REFERENCES ce_mcp_playbook(playbook_code) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_outcome_rule_lookup
  ON ce_mcp_outcome_rule (playbook_code, enabled, priority, outcome_code);


CREATE TABLE IF NOT EXISTS ce_mcp_sql_guardrail (
                                      guardrail_id INTEGER PRIMARY KEY AUTOINCREMENT,
                                      rule_type TEXT NOT NULL CHECK (trim(rule_type) <> ''),
                                      match_value TEXT NOT NULL CHECK (trim(match_value) <> ''),
                                      enabled BOOLEAN NOT NULL DEFAULT 1,
                                      description TEXT,
                                      created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_sql_guardrail_lookup ON ce_mcp_sql_guardrail (enabled, rule_type, match_value);
