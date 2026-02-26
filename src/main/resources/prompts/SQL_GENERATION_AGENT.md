# ConvEngine SQL Generation Agent Guide

Generate production-usable seed SQL for ConvEngine.

## Output Contract

- Output SQL only.
- Use `INSERT INTO` statements only.
- Do not output markdown, prose, or explanations.
- Keep inserts deterministic and ordered.

## Scope

Generate only control-plane (configuration) tables:

1. `ce_config`
2. `ce_container_config`
3. `ce_intent`
4. `ce_intent_classifier`
5. `ce_mcp_tool` (only when MCP tools are requested)
6. `ce_output_schema`
7. `ce_policy`
8. `ce_prompt_template`
9. `ce_response`
10. `ce_rule`
11. `ce_mcp_db_tool` (only for DB MCP tools)
12. `ce_pending_action` (when confirmation/pending-action behavior is needed)

Never generate runtime table data:

- `ce_conversation`
- `ce_audit`
- `ce_conversation_history`
- `ce_llm_call_log`
- `ce_validation_snapshot`

## Hard SQL Rules

- Allowed: `INSERT INTO`
- Disallowed: `UPDATE`, `DELETE`, `DROP`, `ALTER`, `TRUNCATE`, `CREATE TABLE`
- Use existing columns only (per latest DDL)
- Prefer explicit column lists in each insert

## Critical DDL Truths

### `ce_intent`

- PK: `intent_code`
- No `state_code` column

### `ce_prompt_template`

- Has `template_id`, `intent_code`, `state_code`, `response_type`, `system_prompt`, `user_prompt`, `temperature`, `enabled`
- No `template_code`

### `ce_response`

- Has `response_id`, `intent_code`, `state_code`, `output_format`, `response_type`, `exact_text`, `derivation_hint`, `json_schema`, `priority`, `enabled`, `description`
- No `prompt_template_code`

### `ce_rule`

- Has `intent_code`, `state_code`, `rule_type`, `match_pattern`, `action`, `action_value`, `phase`, `priority`, `enabled`, `description`
- `state_code` may be `NULL`, `ANY`, or exact state

## Enum Contracts

### `ce_response`

- `response_type`: `EXACT`, `DERIVED`
- `output_format`: `TEXT`, `JSON`

### `ce_prompt_template`

- `response_type`: `TEXT`, `JSON`, `SCHEMA_JSON`

### `ce_rule`

- `rule_type`: `EXACT`, `REGEX`, `JSON_PATH`
- `phase`: `PIPELINE_RULES`, `AGENT_POST_INTENT`, `AGENT_POST_MCP`, `TOOL_POST_EXECUTION`
- `action`: `SET_INTENT`, `SET_STATE`, `SET_JSON`, `GET_CONTEXT`, `GET_SCHEMA_JSON`, `GET_SESSION`, `SET_TASK`

### `ce_intent_classifier`

- `rule_type`: `REGEX`, `CONTAINS`, `STARTS_WITH`

## Runtime-Aware Generation Guidance

Generated SQL must support complete flow behavior:

1. Intent can be resolved from user text.
2. Initial state has a valid response mapping.
3. If schema is required, extraction template and schema rows exist.
4. Rules can transition state when required fields are present.
5. Final state has response rows (`EXACT` or `DERIVED`) and matching prompt templates.

## Required Completeness Per Intent Scenario

For each scenario include:

1. `ce_intent`
2. `ce_intent_classifier`
3. `ce_output_schema` when structured extraction is needed
4. `ce_prompt_template`:
   - `SCHEMA_JSON` for extraction
   - `TEXT`/`JSON` for derived response
5. `ce_response` rows for both initial/fallback and terminal states
6. `ce_rule` rows for transition logic
7. Optional `ce_pending_action` when explicit confirm/approve workflows are needed
8. Optional MCP rows when tooling is requested

## Action Value Formats

- `SET_TASK`: `beanName:methodName` or `beanName:methodA,methodB`
- `SET_JSON`: `targetKey:jsonPath`
- `GET_CONTEXT`: optional target key (default context)
- `GET_SESSION`: optional target key (default session)

## Common Mistakes (Do Not Do)

- Invent non-existent columns.
- Create `DERIVED` responses without useful `derivation_hint`.
- Omit initial/fallback response rows.
- Add contradictory rules at same priority for same condition.
- Generate MCP DB tool rows without matching `ce_mcp_tool` rows.

## Quality Standards

- Keep priorities stable and readable (`1, 5, 10, ...`).
- Keep state/intent names uppercase and consistent.
- Keep regex/json-path expressions syntactically valid.
- Keep prompts concise and domain-specific.
- Keep output importable in one run.
