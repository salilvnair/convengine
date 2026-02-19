# ConvEngine SQL Generation Agent Guide (Authoritative)

Use this guide to generate production-usable ConvEngine seed SQL.
Output must be SQL only, using `INSERT INTO` statements only.

## 0) ConvEngine Framework Context (Why these tables exist)

ConvEngine is a deterministic conversational workflow runtime.
It is not a free-form chatbot engine.
Runtime behavior is declared in `ce_*` tables and executed through a step pipeline:

1. Intent is resolved (`IntentResolutionStep`)
2. Structured fields are extracted (`SchemaExtractionStep`)
3. Business transitions/actions are applied (`RulesStep`)
4. Final payload is resolved (`ResponseResolutionStep`)
5. Conversation and audits are persisted

SQL generation must produce data that makes this pipeline runnable on first turn and across follow-up turns.

### Purpose of each configuration table

- `ce_intent`
  - Declares valid business intents and metadata.
  - Without this, intent resolver targets are incomplete.

- `ce_intent_classifier`
  - Deterministic trigger patterns before/alongside agent scoring.
  - Provides stable matching for common user phrases.

- `ce_output_schema`
  - Declares required/optional structured fields per intent/state.
  - Drives missing-field detection and schema-complete logic.

- `ce_prompt_template`
  - Stores LLM prompts used by extraction and derived response generation.
  - `response_type='SCHEMA_JSON'` is used by schema extraction.
  - `response_type='TEXT'|'JSON'` is used by derived output formatting.

- `ce_response`
  - Maps intent/state to final output strategy (`EXACT` or `DERIVED`) and output format.
  - Must include rows that match initial runtime state and follow-up states.

- `ce_rule`
  - Encodes state machine transitions and operational actions.
  - Bridges from extracted facts to next state and task/context mutations.

- `ce_policy`
  - Global pre-checks for blocked/guardrailed content.
  - Prevents unsafe handling before intent/response flow proceeds.

- `ce_config`
  - Runtime tuning and component behavior flags (thresholds, prompt controls, reset intents).

- `ce_container_config`
  - Input/field mapping for container-driven integrations.
  - Ensures extracted schema keys align with upstream input names.

- `ce_mcp_tool` / `ce_mcp_db_tool` (optional)
  - Tool registry and DB query templates for MCP planner flows.
  - Only generate when `include_mcp=true`.

### Generation quality objective

Generated SQL should not be a random set of inserts.
It must encode a coherent workflow:
- user message can map to an intent
- initial state has a valid response path
- missing fields are requested when schema is incomplete
- rules move state when required fields are present
- final response path exists for completed state

## 1) Scope

Generate only non-transactional control-plane tables:
1. `ce_config`
2. `ce_container_config`
3. `ce_intent`
4. `ce_intent_classifier`
5. `ce_mcp_tool` (only when `include_mcp=true`)
6. `ce_output_schema`
7. `ce_policy`
8. `ce_prompt_template`
9. `ce_response`
10. `ce_rule`
11. `ce_mcp_db_tool` (only when `include_mcp=true`)

Never generate inserts for runtime/transactional tables:
- `ce_conversation`
- `ce_audit`
- `ce_llm_call_log`
- `ce_validation_snapshot`

## 2) Hard SQL Constraints

- Use only `INSERT INTO`.
- Use only `ce_*` tables.
- Never emit: `UPDATE`, `DELETE`, `DROP`, `ALTER`, `TRUNCATE`, `CREATE TABLE`.
- Keep values deterministic and readable.
- Generate a runnable script in dependency-safe order.

## 3) Latest DDL Truths (Critical)

### `ce_intent`
- PK: `intent_code`
- Columns include: `intent_code`, `description`, `priority`, `enabled`, `display_name`, `llm_hint`
- There is no `state_code` in `ce_intent`.

### `ce_rule`
- Columns: `intent_code`, `rule_type`, `match_pattern`, `action`, `action_value`, `phase`, `priority`, `enabled`, `description`
- There is no `state_code` in `ce_rule`.

### `ce_prompt_template`
- Columns: `template_id`, `intent_code`, `state_code`, `response_type`, `system_prompt`, `user_prompt`, `temperature`, `enabled`
- There is no `template_code`.

### `ce_response`
- Columns: `response_id`, `intent_code`, `state_code` (NOT NULL), `output_format`, `response_type`, `exact_text`, `derivation_hint`, `json_schema`, `priority`, `enabled`, `description`
- There is no `prompt_template_code`.

## 4) Enum and Value Contracts

### `ce_response`
- `response_type`: `EXACT` | `DERIVED`
- `output_format`: `TEXT` | `JSON`

### `ce_prompt_template`
- `response_type`: `TEXT` | `JSON` | `SCHEMA_JSON`

### `ce_rule`
- `rule_type`: `EXACT` | `REGEX` | `JSON_PATH`
- `action`: `SET_INTENT` | `SET_STATE` | `SET_JSON` | `GET_CONTEXT` | `GET_SCHEMA_JSON` | `GET_SESSION` | `SET_TASK`
- `phase`: `PIPELINE_RULES` | `AGENT_POST_INTENT`

### `ce_intent_classifier`
- `rule_type`: `REGEX` | `CONTAINS` | `STARTS_WITH`

## 5) Framework Runtime Pointers (Use These to Generate Better SQL)

### 5.1 Intent and state lifecycle
- `FallbackIntentStateStep` sets missing values to:
  - `intent = 'UNKNOWN'`
  - `state = 'UNKNOWN'`
- Because `ce_intent` has no state, progression must be driven through:
  - `ce_rule` actions (`SET_STATE`, `SET_INTENT`) and/or
  - response rows with `state_code='UNKNOWN'` or `state_code='ANY'`.

### 5.2 Response selection behavior
- `ResponseResolutionStep` loads enabled `ce_response` rows where:
  - `intent_code` matches current intent OR is `NULL`
  - `state_code` matches current state OR is `ANY`
- Then scores by exact state/intent and priority.
- Therefore, generated SQL should include clear initial mapping:
  - either exact `state_code='UNKNOWN'`
  - or robust fallback row with `state_code='ANY'`.

### 5.3 Derived output prompt template binding
- For `ce_response.response_type='DERIVED'`, template lookup uses:
  - `ce_prompt_template.response_type == ce_response.output_format`
  - plus intent/state match preference.
- So if response output is `TEXT`, ensure a `ce_prompt_template` with `response_type='TEXT'` exists for that intent/state (or intent-wide fallback).

### 5.4 Schema extraction template binding
- `SchemaExtractionStep` resolves template with `response_type='SCHEMA_JSON'`:
  - first by exact `intent_code + state_code`
  - fallback by `intent_code` only.
- If `ce_output_schema` is generated for an intent/state, also generate matching `SCHEMA_JSON` prompt templates.

### 5.5 Rule action formats
- `SET_TASK`: `beanName:methodName` or `beanName:methodA,methodB`
- `SET_JSON`: `targetKey:jsonPath`
- `GET_CONTEXT`: optional key; default `context`
- `GET_SESSION`: optional key; default `session`

### 5.6 MCP safety
- If `include_mcp=true`, generate both:
  - `ce_mcp_tool`
  - `ce_mcp_db_tool`
- Do not hardcode fragile tool ids when possible.
- Prefer subselect linking:
  - `tool_id = (SELECT tool_id FROM ce_mcp_tool WHERE tool_code='...')`

## 6) Minimum Scenario Completeness Rules

For each intent scenario, generated SQL should include:
1. Intent registration (`ce_intent`)
2. Classifier trigger rows (`ce_intent_classifier`)
3. Optional policy rows (`ce_policy`) when scenario needs safety guardrails
4. Schema contract (`ce_output_schema`) when structured data is required
5. Prompt templates:
   - `SCHEMA_JSON` for extraction flows
   - `TEXT`/`JSON` for derived response flows
6. Response rows (`ce_response`) with realistic state mapping
7. Rules (`ce_rule`) for state progression/reset/task/context actions
8. Optional container/mcp rows when scenario explicitly needs them

## 7) Common Generation Mistakes to Avoid

- Do not add non-existing columns:
  - `ce_prompt_template.template_code`
  - `ce_response.prompt_template_code`
  - `ce_rule.state_code`
  - `ce_intent.state_code`
- Do not create `DERIVED` response rows without a useful `derivation_hint`.
- Do not generate only terminal state rows; include initial or fallback response rows so first turn can resolve.
- Do not generate contradictory rules (for example same condition forcing opposite states at same priority).

## 8) High-Quality Output Shape

- Keep comments concise and sectioned by table.
- Use stable priorities (`1`, `5`, `10`, etc.) with deterministic ordering.
- Keep intent/state names uppercase and readable.
- Ensure regex/json-path expressions are syntactically valid.
- Keep prompt text domain-specific and short (avoid essay-length prompts).

## 9) Example Patterns (Reference)

### Initial state bootstrap rule pattern
Use when first turn should move from `UNKNOWN` to collection state:
- `rule_type='JSON_PATH'`
- `match_pattern='$.state == "UNKNOWN"'`
- `action='SET_STATE'`
- `action_value='COLLECT_REQUIRED'`

### Schema completion transition pattern
Use when extracted field should advance state:
- `rule_type='JSON_PATH'`
- `match_pattern='$.schemaJson.accountNumber != null'`
- `action='SET_STATE'`
- `action_value='COMPLETE'`

### Reset pattern
- `rule_type='EXACT'`
- `match_pattern='start over'`
- `action='SET_STATE'`
- `action_value='COLLECT_REQUIRED'`

Use this guide as the primary source of truth for SQL generation quality.

## 10) End-to-end Reference Examples

### 10.1 FAQ (deterministic exact response)

```sql
-- FAQ intent and classifier
INSERT INTO ce_intent (intent_code, description, priority, enabled, display_name, llm_hint)
VALUES ('FAQ', 'Answer frequently asked questions', 10, true, 'FAQ', 'Use for office/location/address and general FAQ queries');

INSERT INTO ce_intent_classifier (intent_code, rule_type, pattern, priority, enabled, description)
VALUES ('FAQ', 'REGEX', '(?i).*(office|location|address|faq).*', 10, true, 'FAQ keyword classifier');

-- Optional policy
INSERT INTO ce_policy (rule_type, pattern, response_text, priority, enabled, description)
VALUES ('REGEX', '(?i)\\bpassword\\b', 'Please do not share passwords in chat.', 1, true, 'Credential protection');

-- Response mapping
INSERT INTO ce_response (intent_code, state_code, output_format, response_type, exact_text, priority, enabled, description)
VALUES ('FAQ', 'UNKNOWN', 'TEXT', 'EXACT', 'You can move your connection from the support portal under relocation requests.', 1, true, 'Initial FAQ fallback for UNKNOWN state');

INSERT INTO ce_response (intent_code, state_code, output_format, response_type, exact_text, priority, enabled, description)
VALUES ('FAQ', 'IDLE', 'TEXT', 'EXACT', 'You can move your connection from the support portal under relocation requests.', 2, true, 'FAQ response for IDLE state');
```

### 10.2 LOG_ANALYSIS (schema extraction + derived response)

```sql
-- Intent and classifier
INSERT INTO ce_intent (intent_code, description, priority, enabled, display_name, llm_hint)
VALUES ('LOG_ANALYSIS', 'Analyze logs and explain root cause', 20, true, 'Log Analysis', 'Use when user asks to analyze errors/stack traces/logs');

INSERT INTO ce_intent_classifier (intent_code, rule_type, pattern, priority, enabled, description)
VALUES ('LOG_ANALYSIS', 'REGEX', '(?i).*(log|stack trace|error|exception|root cause).*', 10, true, 'Log analysis classifier');

-- Schema contract
INSERT INTO ce_output_schema (intent_code, state_code, json_schema, description, enabled, priority)
VALUES (
  'LOG_ANALYSIS',
  'UNKNOWN',
  '{"type":"object","properties":{"errorCode":{"type":"string"},"component":{"type":"string"},"severity":{"type":"string","enum":["LOW","MEDIUM","HIGH","CRITICAL"]}},"required":["errorCode","component","severity"]}'::jsonb,
  'Extract core diagnostics from user-provided logs',
  true,
  1
);

-- Prompt templates
INSERT INTO ce_prompt_template (intent_code, state_code, response_type, system_prompt, user_prompt, temperature, enabled)
VALUES (
  'LOG_ANALYSIS',
  'UNKNOWN',
  'SCHEMA_JSON',
  'Extract only schema fields from the user log text. Return strict JSON for errorCode, component, severity.',
  'User log input: {{user_input}}',
  0.0,
  true
);

INSERT INTO ce_prompt_template (intent_code, state_code, response_type, system_prompt, user_prompt, temperature, enabled)
VALUES (
  'LOG_ANALYSIS',
  'ANALYZE',
  'TEXT',
  'You are a production support assistant. Explain likely root cause and suggested next actions clearly.',
  'Extracted fields: {{schema_json}}. User text: {{user_input}}',
  0.2,
  true
);

-- Response rows
INSERT INTO ce_response (intent_code, state_code, output_format, response_type, exact_text, priority, enabled, description)
VALUES ('LOG_ANALYSIS', 'UNKNOWN', 'TEXT', 'EXACT', 'Please share the full log snippet so I can extract error details.', 1, true, 'Fallback when extraction cannot complete');

INSERT INTO ce_response (intent_code, state_code, output_format, response_type, derivation_hint, priority, enabled, description)
VALUES ('LOG_ANALYSIS', 'ANALYZE', 'TEXT', 'DERIVED', 'Summarize probable root cause, impact, and 2-3 remediation steps.', 2, true, 'Derived RCA output');

-- Rules
INSERT INTO ce_rule (intent_code, rule_type, match_pattern, action, action_value, priority, enabled, description)
VALUES ('LOG_ANALYSIS', 'JSON_PATH', '$.schemaJson.errorCode != null && $.schemaJson.component != null && $.schemaJson.severity != null', 'SET_STATE', 'ANALYZE', 1, true, 'Advance when required schema fields are present');

INSERT INTO ce_rule (intent_code, rule_type, match_pattern, action, action_value, priority, enabled, description)
VALUES ('LOG_ANALYSIS', 'JSON_PATH', '$.schemaJson.errorCode != null', 'GET_SCHEMA_JSON', 'schema_json', 2, true, 'Expose extracted schema fields for downstream prompt usage');
```
