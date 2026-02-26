DROP TABLE IF EXISTS ce_mcp_db_tool;
DROP TABLE IF EXISTS ce_mcp_planner;
DROP TABLE IF EXISTS ce_conversation_history;
DROP TABLE IF EXISTS ce_audit;
DROP TABLE IF EXISTS ce_pending_action;
DROP TABLE IF EXISTS ce_rule;
DROP TABLE IF EXISTS ce_response;
DROP TABLE IF EXISTS ce_prompt_template;
DROP TABLE IF EXISTS ce_policy;
DROP TABLE IF EXISTS ce_output_schema;
DROP TABLE IF EXISTS ce_mcp_tool;
DROP TABLE IF EXISTS ce_llm_call_log;
DROP TABLE IF EXISTS ce_intent_classifier;
DROP TABLE IF EXISTS ce_intent;
DROP TABLE IF EXISTS ce_conversation;
DROP TABLE IF EXISTS ce_container_config;
DROP TABLE IF EXISTS ce_config;

CREATE TABLE ce_config (
  config_id INTEGER NOT NULL PRIMARY KEY,
  config_type TEXT NOT NULL,
  config_key TEXT NOT NULL,
  config_value TEXT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE UNIQUE INDEX ux_ce_config_type_key ON ce_config (config_type, config_key);

CREATE TABLE ce_container_config (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  intent_code TEXT NOT NULL,
  state_code TEXT NOT NULL,
  page_id INTEGER NOT NULL,
  section_id INTEGER NOT NULL,
  container_id INTEGER NOT NULL,
  input_param_name TEXT NOT NULL,
  priority INTEGER NOT NULL DEFAULT 1,
  enabled BOOLEAN NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX idx_ce_validation_config_lookup ON ce_container_config (intent_code, state_code, enabled, priority);

CREATE TABLE ce_conversation (
  conversation_id TEXT NOT NULL PRIMARY KEY,
  status TEXT NOT NULL,
  intent_code TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (trim(intent_code) <> ''),
  state_code TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (trim(state_code) <> ''),
  context_json TEXT NOT NULL DEFAULT '{}',
  last_user_text TEXT,
  last_assistant_json TEXT,
  input_params_json TEXT NOT NULL DEFAULT '{}',
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
  updated_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX idx_ce_conversation_status ON ce_conversation (status);
CREATE INDEX idx_ce_conversation_updated ON ce_conversation (updated_at);

CREATE TABLE ce_intent (
  intent_code TEXT NOT NULL PRIMARY KEY,
  description TEXT NOT NULL,
  priority INTEGER NOT NULL DEFAULT 100,
  enabled BOOLEAN NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
  display_name TEXT,
  llm_hint TEXT
);
CREATE INDEX ix_ce_intent_enabled_priority ON ce_intent (enabled, priority, intent_code);

CREATE TABLE ce_intent_classifier (
  classifier_id INTEGER PRIMARY KEY AUTOINCREMENT,
  intent_code TEXT NOT NULL,
  state_code TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (trim(state_code) <> ''),
  rule_type TEXT NOT NULL,
  pattern TEXT NOT NULL,
  priority INTEGER NOT NULL,
  enabled BOOLEAN DEFAULT 1,
  description TEXT
);

CREATE TABLE ce_llm_call_log (
  llm_call_id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id TEXT NOT NULL,
  intent_code TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (trim(intent_code) <> ''),
  state_code TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (trim(state_code) <> ''),
  provider TEXT NOT NULL,
  model TEXT NOT NULL,
  temperature NUMERIC(3,2),
  prompt_text TEXT NOT NULL,
  user_context TEXT NOT NULL,
  response_text TEXT,
  success BOOLEAN NOT NULL,
  error_message TEXT,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX idx_ce_llm_log_conversation ON ce_llm_call_log (conversation_id);
CREATE INDEX idx_ce_llm_log_intent_state ON ce_llm_call_log (intent_code, state_code);

CREATE TABLE ce_mcp_tool (
  tool_id INTEGER PRIMARY KEY AUTOINCREMENT,
  tool_code TEXT NOT NULL UNIQUE,
  tool_group TEXT NOT NULL,
  intent_code TEXT NOT NULL CHECK (trim(intent_code) <> ''),
  state_code TEXT NOT NULL CHECK (trim(state_code) <> ''),
  enabled BOOLEAN NOT NULL DEFAULT 1,
  description TEXT,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX idx_ce_mcp_tool_enabled ON ce_mcp_tool (enabled, intent_code, state_code, tool_group, tool_code);

CREATE TABLE ce_output_schema (
  schema_id INTEGER PRIMARY KEY AUTOINCREMENT,
  intent_code TEXT NOT NULL,
  state_code TEXT NOT NULL,
  json_schema TEXT NOT NULL,
  description TEXT,
  enabled BOOLEAN DEFAULT 1,
  priority INTEGER NOT NULL
);
CREATE INDEX idx_ce_output_schema_lookup ON ce_output_schema (intent_code, state_code, enabled, priority);

CREATE TABLE ce_policy (
  policy_id INTEGER PRIMARY KEY AUTOINCREMENT,
  rule_type TEXT NOT NULL,
  pattern TEXT NOT NULL,
  response_text TEXT NOT NULL,
  priority INTEGER NOT NULL DEFAULT 10,
  enabled BOOLEAN NOT NULL DEFAULT 1,
  description TEXT,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX idx_ce_policy_priority ON ce_policy (enabled, priority);

CREATE TABLE ce_prompt_template (
  template_id INTEGER PRIMARY KEY AUTOINCREMENT,
  intent_code TEXT NOT NULL CHECK (trim(intent_code) <> ''),
  state_code TEXT NOT NULL CHECK (trim(state_code) <> ''),
  response_type TEXT NOT NULL,
  system_prompt TEXT NOT NULL,
  user_prompt TEXT NOT NULL,
  temperature NUMERIC(3,2) NOT NULL DEFAULT 0.0,
  enabled BOOLEAN NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX idx_ce_prompt_template_lookup ON ce_prompt_template (response_type, intent_code, state_code, enabled);

CREATE TABLE ce_response (
  response_id INTEGER PRIMARY KEY AUTOINCREMENT,
  intent_code TEXT NOT NULL CHECK (trim(intent_code) <> ''),
  state_code TEXT NOT NULL CHECK (trim(state_code) <> ''),
  output_format TEXT NOT NULL,
  response_type TEXT NOT NULL,
  exact_text TEXT,
  derivation_hint TEXT,
  json_schema TEXT,
  priority INTEGER NOT NULL DEFAULT 100,
  enabled BOOLEAN NOT NULL DEFAULT 1,
  description TEXT,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX idx_ce_response_intent_state ON ce_response (intent_code, state_code, enabled, priority);
CREATE INDEX idx_ce_response_lookup ON ce_response (state_code, enabled, priority);

CREATE TABLE ce_rule (
  rule_id INTEGER PRIMARY KEY AUTOINCREMENT,
  phase TEXT NOT NULL DEFAULT 'PRE_RESPONSE_RESOLUTION',
  intent_code TEXT NOT NULL CHECK (trim(intent_code) <> ''),
  state_code TEXT NOT NULL CHECK (trim(state_code) <> ''),
  rule_type TEXT NOT NULL,
  match_pattern TEXT NOT NULL,
  action TEXT NOT NULL,
  action_value TEXT,
  priority INTEGER NOT NULL DEFAULT 100,
  enabled BOOLEAN NOT NULL DEFAULT 1,
  description TEXT,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX idx_ce_rule_priority ON ce_rule (enabled, phase, state_code, priority);

CREATE TABLE ce_pending_action (
  pending_action_id INTEGER PRIMARY KEY AUTOINCREMENT,
  intent_code TEXT NOT NULL CHECK (trim(intent_code) <> ''),
  state_code TEXT NOT NULL CHECK (trim(state_code) <> ''),
  action_key TEXT NOT NULL,
  bean_name TEXT NOT NULL,
  method_names TEXT NOT NULL,
  priority INTEGER NOT NULL DEFAULT 100,
  enabled BOOLEAN NOT NULL DEFAULT 1,
  description TEXT,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX idx_ce_pending_action_lookup ON ce_pending_action (enabled, action_key, intent_code, state_code, priority);

CREATE TABLE ce_audit (
  audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id TEXT NOT NULL,
  stage TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
  FOREIGN KEY (conversation_id) REFERENCES ce_conversation(conversation_id) ON DELETE CASCADE
);
CREATE INDEX idx_ce_audit_conversation ON ce_audit (conversation_id, created_at DESC);

CREATE TABLE ce_conversation_history (
  history_id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id TEXT NOT NULL,
  user_input TEXT NOT NULL,
  assistant_output TEXT,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
  modified_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
  FOREIGN KEY (conversation_id) REFERENCES ce_conversation(conversation_id) ON DELETE CASCADE
);
CREATE INDEX idx_ce_conversation_history_conv ON ce_conversation_history (conversation_id, created_at DESC);

CREATE TABLE ce_mcp_db_tool (
  tool_id INTEGER NOT NULL PRIMARY KEY,
  dialect TEXT NOT NULL DEFAULT 'POSTGRES',
  sql_template TEXT NOT NULL,
  param_schema TEXT NOT NULL,
  safe_mode BOOLEAN NOT NULL DEFAULT 1,
  max_rows INTEGER NOT NULL DEFAULT 200,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
  allowed_identifiers TEXT,
  FOREIGN KEY (tool_id) REFERENCES ce_mcp_tool(tool_id) ON DELETE CASCADE
);
CREATE INDEX idx_ce_mcp_db_tool_dialect ON ce_mcp_db_tool (dialect);

CREATE TABLE ce_mcp_planner (
  planner_id INTEGER PRIMARY KEY AUTOINCREMENT,
  intent_code TEXT NOT NULL CHECK (trim(intent_code) <> ''),
  state_code TEXT NOT NULL CHECK (trim(state_code) <> ''),
  system_prompt TEXT NOT NULL,
  user_prompt TEXT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX idx_ce_mcp_planner_scope ON ce_mcp_planner (enabled, intent_code, state_code, planner_id);
