# ConvEngine (Java) - AGENT.md

## Overview

ConvEngine is a deterministic, configuration-driven conversational workflow engine.

It is not an unconstrained chatbot runtime. Business behavior is declared in `ce_*` tables, constrained by config, and executed by an auditable step pipeline.

Current library baseline: `2.0.10`.

## Current Release Line (`2.0.0` to `2.0.10`)

### `2.0.0` foundation
- step-pipeline architecture became the core execution model
- rule-first behavior model (`ce_rule`) became the main transition mechanism
- pending actions, tool orchestration, state graph, memory, and replay scaffolding became part of the runtime

### `2.0.6`
- static cache reliability and diagnostics improved
- cache analysis / refresh operational tooling became part of the expected runtime model

### `2.0.7`
- `ce_mcp_planner` introduced scoped MCP planner prompts
- `ce_mcp_tool` / `ce_mcp_planner` scope validation became strict (`ANY`, `UNKNOWN`, or exact; no null/blank wildcard rows)
- richer MCP lifecycle metadata was added under `context.mcp.*`
- advanced HTTP MCP handler models were added

### `2.0.8`
- `ce_verbose` introduced as a first-class runtime progress/error control table
- stream envelope added explicit `AUDIT` / `VERBOSE` event support
- `EngineSession.stepInfos` added deterministic step telemetry
- MCP schema-incomplete skip behavior became explicit and auditable

### `2.0.9`
- `CorrectionStep` became a real routing step in the main pipeline
- `ce_prompt_template.interaction_mode` and `interaction_contract` became actual runtime routing inputs
- prompt and verbose rendering consolidated through the shared Thymeleaf-backed renderer
- `DialogueActStep` added richer audit checkpoints and `POST_DIALOGUE_ACT` rule interaction
- DBKG (`dbkg.*`) introduced the DB-driven MCP investigation model

### `2.0.10`
- Current repo baseline.
- Treat the current 27-step pipeline, scoped MCP model, `ce_verbose`, and DBKG as the canonical runtime shape.

## Runtime Architecture

### Request entry and engine flow
1. `ConversationController.message(...)` receives request and builds `EngineContext`.
2. `DefaultConversationalEngine.process(...)` opens `EngineSession` from `EngineSessionFactory`.
3. `EnginePipelineFactory` builds the DAG-ordered pipeline.
4. Pipeline executes steps in runtime order.
5. `PersistConversationStep` persists final conversation state.
6. `PipelineEndGuardStep` closes timings and terminal guards.
7. Controller maps `EngineResult` to API DTO and returns.

## Canonical Pipeline (Current 27 Steps)

1. `LoadOrCreateConversationStep`
2. `CacheInspectAuditStep`
3. `ResetConversationStep`
4. `PersistConversationBootstrapStep`
5. `AuditUserInputStep`
6. `PolicyEnforcementStep`
7. `DialogueActStep`
8. `InteractionPolicyStep`
9. `CorrectionStep`
10. `ActionLifecycleStep`
11. `DisambiguationStep`
12. `GuardrailStep`
13. `IntentResolutionStep`
14. `ResetResolvedIntentStep`
15. `FallbackIntentStateStep`
16. `AddContainerDataStep`
17. `PendingActionStep`
18. `ToolOrchestrationStep`
19. `McpToolStep`
20. `SchemaExtractionStep`
21. `AutoAdvanceStep`
22. `RulesStep`
23. `StateGraphStep`
24. `ResponseResolutionStep`
25. `MemoryStep`
26. `PersistConversationStep`
27. `PipelineEndGuardStep`

Order is enforced by step annotations, dependency rules, and DAG validation. Do not document or code against the older shortened pipeline.

## Core Operating Model

1. Bootstrap and restore conversation state
2. Apply early reset / policy / guard boundaries
3. Resolve dialogue act before intent when possible
4. Route correction / confirmation / retry in place
5. Resolve or maintain intent + state
6. Extract structured fields by schema
7. Compute transitions via rules and policy
8. Execute direct tools or planner-driven MCP when eligible
9. Resolve final output by exact/derived response mapping
10. Persist runtime state, history, audit, and timing metadata

## Data Model Contracts

### Static control-plane tables
- `ce_config`
- `ce_container_config`
- `ce_intent`
- `ce_intent_classifier`
- `ce_output_schema`
- `ce_policy`
- `ce_prompt_template`
- `ce_response`
- `ce_rule`
- `ce_pending_action`
- `ce_mcp_tool`
- `ce_mcp_db_tool`
- `ce_mcp_planner`
- `ce_verbose`

### Runtime / transactional tables
- `ce_conversation`
- `ce_audit`
- `ce_conversation_history`
- `ce_llm_call_log`

### DBKG control-plane tables
- `ce_mcp_case_type`
- `ce_mcp_case_signal`
- `ce_mcp_playbook`
- `ce_mcp_playbook_signal`
- `ce_mcp_domain_entity`
- `ce_mcp_domain_relation`
- `ce_mcp_system_node`
- `ce_mcp_api_flow`
- `ce_mcp_system_relation`
- `ce_mcp_db_object`
- `ce_mcp_db_column`
- `ce_mcp_db_join_path`
- `ce_mcp_status_dictionary`
- `ce_mcp_id_lineage`
- `ce_mcp_executor_template`
- `ce_mcp_query_template`
- `ce_mcp_query_param_rule`
- `ce_mcp_playbook_step`
- `ce_mcp_playbook_transition`
- `ce_mcp_outcome_rule`

## Must-Keep Table Contracts

### `ce_prompt_template`

Columns include:
- `template_id`
- `intent_code`
- `state_code`
- `response_type`
- `system_prompt`
- `user_prompt`
- `temperature`
- `interaction_mode`
- `interaction_contract`
- `enabled`

There is no `template_code`.

`interaction_mode` is the coarse turn semantic bucket. Supported values:
- `NORMAL`
- `IDLE`
- `COLLECT`
- `CONFIRM`
- `PROCESSING`
- `FINAL`
- `ERROR`
- `DISAMBIGUATE`
- `FOLLOW_UP`
- `PENDING_ACTION`
- `REVIEW`

`interaction_contract` is extensible JSON text. Recommended shape:
- `{"allows":["affirm","edit","retry","reset"],"expects":["structured_input"]}`

Use `interaction_mode` for broad semantics and `interaction_contract` for fine-grained behavior. `CorrectionStep` depends on this contract.

### `ce_response`

Columns include:
- `response_id`
- `intent_code`
- `state_code`
- `output_format`
- `response_type`
- `exact_text`
- `derivation_hint`
- `json_schema`
- `priority`
- `enabled`
- `description`

There is no `prompt_template_code`.

### `ce_rule`

Current important values:
- `rule_type`: `EXACT | REGEX | JSON_PATH`
- `phase`:
  - `POST_DIALOGUE_ACT`
  - `POST_SCHEMA_EXTRACTION`
  - `PRE_AGENT_MCP`
  - `PRE_RESPONSE_RESOLUTION`
  - `POST_AGENT_INTENT`
  - `POST_AGENT_MCP`
  - `POST_TOOL_EXECUTION`

Current action surface:
- `SET_INTENT`
- `SET_STATE`
- `SET_DIALOGUE_ACT`
- `SET_JSON`
- `GET_CONTEXT`
- `GET_SCHEMA_JSON`
- `GET_SESSION`
- `SET_TASK`
- `SET_INPUT_PARAM`

Use `ce_rule` before adding Java branching.

### `ce_pending_action`

Catalog of action candidates by intent/state/action key.

Important:
- runtime lifecycle is not stored in this table
- runtime lifecycle (`OPEN`, `IN_PROGRESS`, `EXECUTED`, `REJECTED`, `EXPIRED`) lives in context as `pending_action_runtime`

### `ce_mcp_tool`

Critical current contract:
- `intent_code` is mandatory
- `state_code` is mandatory
- null/blank scope is invalid
- valid scope values are:
  - exact configured values
  - `ANY`
  - `UNKNOWN`

Supported canonical groups:
- `DB`
- `HTTP_API`
- `WORKFLOW_ACTION`
- `DOCUMENT_RETRIEVAL`
- `CALCULATOR_TRANSFORM`
- `NOTIFICATION`
- `FILES`

### `ce_mcp_planner`

Planner prompt selection order:
1. exact `intent_code + state_code`
2. exact `intent_code + ANY`
3. `ANY + ANY`
4. legacy `ce_config` fallback

Like `ce_mcp_tool`, scope is explicit and validated at startup.

### `ce_verbose`

Current contract:
- startup validated
- statically cached
- matched by:
  - `intent_code`
  - `state_code`
  - `determinant`
  - `step_match`
  - `step_value`
  - optional `rule_id`
  - optional `tool_code`
  - `priority`

`step_match` must be:
- `EXACT`
- `REGEX`
- `JSON_PATH`

Do not document or seed invalid `step_match` values.

Current SQL observability determinants:
- `MCP_DB_SQL_EXECUTION` on `McpDbExecutor`
- `DBKG_QUERY_SQL_EXECUTION` on `DbkgQueryTemplateStepExecutor`

Both publish metadata with:
- `sql`
- `params`
- `row_count`
- `rows`

## Step Design Rules

- Keep steps composable and side-effect scoped.
- Prefer explicit audit and deterministic mutation over hidden behavior.
- Keep order constraints explicit with annotations and DAG rules.
- For safety steps (`PolicyEnforcementStep`, `GuardrailStep`, `StateGraphStep`), fail-soft unless config explicitly requires fail-closed behavior.
- Do not move domain logic into step classes when the behavior belongs in `ce_rule`, `ce_prompt_template`, or DBKG metadata tables.

## Rule-First Philosophy

Before adding Java branching:
- check if behavior can be expressed in `ce_rule`
- check the correct phase first
- check whether the behavior belongs in:
  - `SET_INPUT_PARAM`
  - `SET_TASK`
  - `SET_DIALOGUE_ACT`
  - `SET_JSON`
- check whether the prompt contract (`interaction_mode`, `interaction_contract`) already models the intended routing

## Audit Expectations

Any non-trivial decision should emit a visible audit stage.

At minimum preserve visibility for:
- dialogue act classification
- interaction policy decisions
- correction routing
- pending action lifecycle / execution / rejection
- guardrail allow / deny
- disambiguation requirements
- state graph validation / violation
- direct tool orchestration request / result / error
- MCP planner input / output / tool call / tool result / final answer
- response resolution selection

## MCP + Tooling

There are two distinct tool paths:

### Direct tool path
- `ToolOrchestrationStep`
- one request-driven tool
- writes `context.mcp.toolExecution.*`
- runs `POST_TOOL_EXECUTION` rules

### Planner MCP path
- `McpToolStep`
- planner loop (`CALL_TOOL` / `ANSWER`)
- writes `context.mcp.observations[]`
- writes `context.mcp.finalAnswer`
- runs `PRE_AGENT_MCP` and `POST_AGENT_MCP` rule phases around the MCP lifecycle

### MCP guardrails

Current MCP next-tool guard model:
- controlled by `convengine.mcp.guardrail.*`
- `failClosed` can block if allowed-next rules are missing
- blocked-next behavior must remain deterministic and auditable

## DBKG (DB-Driven MCP Investigation Model)

DBKG extends MCP from simple tool calling into a database-driven investigation runtime.

### DBKG tool chain
1. `dbkg.case.resolve`
2. `dbkg.knowledge.lookup`
3. `dbkg.investigate.plan`
4. `dbkg.playbook.validate`
5. `dbkg.investigate.execute`
6. planner `ANSWER`

### DBKG Java runtime chain
1. `DbkgCaseResolveToolHandler`
2. `DbkgCaseResolver`
3. `DbkgKnowledgeLookupToolHandler`
4. `DbkgKnowledgeLookupService`
5. `DbkgInvestigatePlanToolHandler`
6. `DbkgPlaybookResolver`
7. `DbkgPlaybookValidateToolHandler`
8. `DbkgPlaybookValidator`
9. `DbkgInvestigateExecuteToolHandler`
10. `DbkgPlaybookEngine`

### DBKG design rules
- Do not hardcode project-specific investigation logic in Java handlers.
- Keep case detection in:
  - `ce_mcp_case_type`
  - `ce_mcp_case_signal`
- Keep playbook routing and graph execution in:
  - `ce_mcp_playbook*`
- Keep SQL in:
  - `ce_mcp_query_template`
  - `ce_mcp_query_param_rule`
- Keep final diagnosis logic in:
  - `ce_mcp_outcome_rule`
- Use live schema introspection for physical schema whenever possible.
- Use overlay tables (`ce_mcp_db_object`, `ce_mcp_db_column`, `ce_mcp_db_join_path`) only for semantic hints and preferred joins.
- Validation must happen before execute.

### DBKG hard guard

`McpToolStep` must block `dbkg.investigate.execute` unless a successful `dbkg.playbook.validate` observation is already present in the same MCP loop.

Current explicit block reason:
- `DBKG_VALIDATION_REQUIRED_BEFORE_EXECUTION`

## Prompt and Rendering Rules

- Prompt and verbose rendering should use the shared Thymeleaf-backed renderer path.
- Do not add parallel ad hoc renderers for prompts or verbose messages.
- Legacy `{{var}}` still works, but the native path is the shared renderer.
- Prompt/session variables now include:
  - `session`
  - `inputParams`
  - `rawInputParams`
  - `context`
  - `schema`
  - `schemaJson`
  - `promptVars`
  - `standalone_query`
  - `resolved_user_input`

## Framework Performance Patterns

Do not reintroduce synchronous configuration-table lookups into the request path.

- Static configuration tables should resolve through the cache layer.
- Runtime state persistence should remain isolated from the core decision path.
- Async persistence and event fan-out should remain the preferred model for heavy writes.

## Source of Truth Order

When implementing or documenting behavior:
1. DDL / SQL resources (`src/main/resources/sql/**`)
2. `ce_*` table contracts
3. Step pipeline contracts
4. API/controller mapping
5. Docs

If docs/examples conflict with DDL or actual step order, DDL and code win.

## Documentation Discipline

When behavior changes:
1. Update `README.md`
2. Update this `AGENT.md`
3. Update related docs and examples
4. Keep SQL assets, docs, and runtime enum values aligned

## Release Hygiene Checklist

- DDL and seed scripts aligned
- Rule phases aligned across code, docs, and examples
- Step list aligned to the real 27-step runtime
- New input param keys centralized in constants
- New audit stages centralized in enums/constants
- MCP scope rules documented with current strict behavior
- `ce_verbose` docs and seeds aligned with real determinants and matcher types
- DBKG docs and seeds aligned with the current metadata table set
- Prompt and verbose rendering continue to use the shared renderer path
