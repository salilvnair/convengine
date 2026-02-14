# ConvEngine SQL Generation Agent Guide

This guide defines how to generate ConvEngine configuration SQL inserts.
Generate only configuration/control-plane seed data. Do not write runtime/transaction tables.

## 1) Table Scope

### Non-transactional tables to generate (latest DDL order, transactional excluded)
- `ce_config`
- `ce_container_config`
- `ce_intent`
- `ce_intent_classifier`
- `ce_mcp_tool` (only when `include_mcp=true`)
- `ce_output_schema`
- `ce_policy`
- `ce_prompt_template`
- `ce_response`
- `ce_rule`
- `ce_mcp_db_tool` (only when `include_mcp=true`)

### Transactional/runtime tables to exclude
- `ce_conversation`
- `ce_audit`
- `ce_llm_call_log`
- `ce_validation_snapshot`

## 2) Valid Enum/Code Values

### ce_response
- `response_type`: `EXACT` | `DERIVED`
- `output_format`: `TEXT` | `JSON`

### ce_prompt_template
- `response_type`: `TEXT` | `JSON` | `SCHEMA_JSON`

### ce_rule
- `rule_type`: `EXACT` | `REGEX` | `JSON_PATH`
- `action`: `SET_INTENT` | `SET_STATE` | `SET_JSON` | `GET_CONTEXT` | `GET_SCHEMA_JSON` | `GET_SESSION` | `SET_TASK`

### ce_intent_classifier
- `rule_type`: `REGEX` | `CONTAINS` | `STARTS_WITH`

## 3) Table Notes and Required Columns

### ce_config
- PK: `config_id` (explicit integer, non-identity)
- Unique: (`config_type`, `config_key`)
- Required: `config_id`, `config_type`, `config_key`, `config_value`
- Common usage:
  - `AgentIntentResolver` prompts and thresholds
  - `AgentIntentCollisionResolver` prompts
  - reset intent key: `RESET_INTENT_CODES`
- Example:
  - `INSERT INTO ce_config (config_id, config_type, config_key, config_value, enabled) VALUES (100, 'ResetResolvedIntentStep', 'RESET_INTENT_CODES', 'RESET_SESSION,START_OVER', true);`

### ce_container_config
- PK: `id` (identity)
- Required:
  - `intent_code`, `state_code`
  - `page_id`, `section_id`, `container_id`
  - `input_param_name`
- `input_param_name` must match schema field names used by extraction flow
- Example:
  - `INSERT INTO ce_container_config (intent_code, state_code, page_id, section_id, container_id, input_param_name, priority, enabled) VALUES ('DISCONNECT_ELECTRICITY', 'COLLECT_REQUIRED', 1, 10, 101, 'accountNumber', 1, true);`

### ce_intent
- PK: `intent_code`
- Required: `intent_code`, `description`
- Useful: `priority`, `enabled`, `display_name`, `llm_hint`
- Example:
  - `INSERT INTO ce_intent (intent_code, description, display_name, llm_hint, priority, enabled) VALUES ('DISCONNECT_ELECTRICITY', 'Handle electricity disconnection flow', 'Disconnect Electricity', 'Use for meter/account disconnect requests', 10, true);`

### ce_intent_classifier
- PK: `classifier_id` (identity)
- Required: `intent_code`, `rule_type`, `pattern`, `priority`
- Purpose: deterministic intent hints before/alongside agent resolver
- Example:
  - `INSERT INTO ce_intent_classifier (intent_code, rule_type, pattern, priority, enabled, description) VALUES ('DISCONNECT_ELECTRICITY', 'CONTAINS', 'disconnect my electricity', 10, true, 'Primary keyword trigger');`

### ce_mcp_tool (only include_mcp=true)
- PK: `tool_id` (identity)
- Required: `tool_code`, `tool_group`
- Example:
  - `INSERT INTO ce_mcp_tool (tool_code, tool_group, enabled, description) VALUES ('postgres.query', 'DB', true, 'Execute readonly SQL');`

### ce_output_schema
- PK: `schema_id` (identity)
- Required: `intent_code`, `state_code`, `json_schema`, `priority`
- `json_schema` must be valid JSON schema object
- Example:
  - `INSERT INTO ce_output_schema (intent_code, state_code, json_schema, description, enabled, priority) VALUES ('DISCONNECT_ELECTRICITY', 'COLLECT_REQUIRED', '{"type":"object","properties":{"accountNumber":{"type":"string"}},"required":["accountNumber"]}'::jsonb, 'Required intake fields', true, 1);`

### ce_policy
- PK: `policy_id` (identity)
- Required: `rule_type`, `pattern`, `response_text`
- Purpose: global safety/compliance checks (block or guardrail text)
- Example:
  - `INSERT INTO ce_policy (rule_type, pattern, response_text, priority, enabled, description) VALUES ('REGEX', '(?i)\\bpassword\\b', 'Please do not share passwords in chat.', 1, true, 'Credential protection');`

### ce_prompt_template
- PK: `template_id` (identity)
- Required: `response_type`, `system_prompt`, `user_prompt`
- Optional targeting: `intent_code`, `state_code`
- For schema extraction use `response_type='SCHEMA_JSON'`
- Example:
  - `INSERT INTO ce_prompt_template (intent_code, response_type, system_prompt, user_prompt, temperature, enabled, state_code) VALUES ('DISCONNECT_ELECTRICITY', 'SCHEMA_JSON', 'Extract only schema fields.', 'User input: {{user_input}}', 0.0, true, 'COLLECT_REQUIRED');`

### ce_response
- PK: `response_id` (identity)
- Required: `state_code`, `output_format`, `response_type`
- `EXACT`: use `exact_text` (or JSON text payload when `output_format=JSON`)
- `DERIVED`: use `derivation_hint`, and provide matching prompt templates
- Example:
  - `INSERT INTO ce_response (intent_code, state_code, output_format, response_type, exact_text, priority, enabled, description) VALUES ('DISCONNECT_ELECTRICITY', 'COLLECT_REQUIRED', 'TEXT', 'EXACT', 'Please share your account number to continue.', 1, true, 'Prompt for required field');`

### ce_rule
- PK: `rule_id` (identity)
- Required: `rule_type`, `match_pattern`, `action`
- Optional: `intent_code`, `action_value`, `priority`, `enabled`
- Example:
  - `INSERT INTO ce_rule (intent_code, rule_type, match_pattern, action, action_value, priority, enabled, description) VALUES ('DISCONNECT_ELECTRICITY', 'EXACT', 'start over', 'SET_STATE', 'COLLECT_REQUIRED', 1, true, 'Reset collection state');`

### ce_mcp_db_tool (only include_mcp=true)
- PK/FK: `tool_id` references `ce_mcp_tool.tool_id`
- Required: `tool_id`, `dialect`, `sql_template`, `param_schema`
- Keep `safe_mode=true` by default and bounded `max_rows`
- Example:
  - `INSERT INTO ce_mcp_db_tool (tool_id, dialect, sql_template, param_schema, safe_mode, max_rows) VALUES (1, 'POSTGRES', 'SELECT * FROM outage_ticket WHERE account_no = :accountNumber', '{"type":"object","properties":{"accountNumber":{"type":"string"}},"required":["accountNumber"]}'::jsonb, true, 100);`

## 4) Dependency Order

Use latest DDL order for generated script (transactional tables skipped):
1. `ce_config`
2. `ce_container_config`
3. `ce_intent`
4. `ce_intent_classifier`
5. `ce_mcp_tool` (if MCP enabled)
6. `ce_output_schema`
7. `ce_policy`
8. `ce_prompt_template`
9. `ce_response`
10. `ce_rule`
11. `ce_mcp_db_tool` (if MCP enabled, after `ce_mcp_tool`)

## 5) DDL Seed Note

- In `src/main/resources/sql/ddl.sql`, the `ce_config` seed block for `config_id=9` currently has a duplicated column-list line before `VALUES`.
- Keep only one column-list line:
  - `INSERT INTO ce_config (config_id, config_type, config_key, config_value, enabled, created_at)`

## 6) Script Quality Rules

- SQL only; no markdown.
- Only `INSERT INTO`.
- Use readable deterministic literals and stable priorities.
- Keep prompts/scenarios domain-specific (not generic lorem ipsum).
- Ensure every generated row is internally consistent with intent/state/scenario.
