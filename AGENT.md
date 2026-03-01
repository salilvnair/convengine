
# ConvEngine (Java) - AGENT.md

## Overview

ConvEngine is a deterministic, configuration-driven conversational workflow engine.

It is not an open-ended chatbot runtime. Business behavior is declared in `ce_*` tables and executed by an auditable step pipeline.

Current library baseline: `1.0.15`.

## Release Notes (Latest)

### 1.0.15
- `SchemaExtractionStep` was refactored to a thin orchestrator; schema-heavy calculations moved to schema resolver provider contract.
- Added provider-owned computation contract:
  - `ConvEngineSchemaComputation`
  - `ConvEngineSchemaResolver#compute(...)`
  - `ConvEngineSchemaResolver#sanitizeExtractedJson(...)`
  - `ConvEngineSchemaResolver#mergeContextJson(...)`
- `DefaultConvEngineSchemaResolver` now owns sanitization, merge, completeness, missing-fields, and missing-field-options behavior.
- Provider override model is now clean and explicit:
  - consumers can register their own `ConvEngineSchemaResolver` bean
  - higher precedence via Spring `@Order` is respected by resolver factory selection.
- Centralized input param keys in `ConvEngineInputParamKey`; removed hardcoded `putInputParam("...")` keys across step/intent/rule flows.
- Centralized fixed audit stages in `ConvEngineAuditStage`; migrated fixed literals across engine, MCP, response, and intent components.
- Added centralized payload map keys in `ConvEnginePayloadKey`; replaced payload key string literals (`payload.put("...")`) across ConvEngine.

### 1.0.14
- Added `ce_rule.state_code` support (`NULL`, `ANY`, exact match) to scope rule execution by state and reduce unnecessary evaluations.
- Audit persistence strategy split with synchronous conversation history persistence guarantees.

## Runtime Architecture

### Request entry and engine flow
1. `ConversationController.message(...)` receives request and builds `EngineContext`.
2. `DefaultConversationalEngine.process(...)` opens `EngineSession` from `EngineSessionFactory`.
3. `EnginePipelineFactory` builds a DAG-ordered pipeline.
4. Pipeline executes steps in order.
5. `PersistConversationStep` and `PipelineEndGuardStep` finalize output and timings.
6. Controller maps `EngineResult` to API DTO and returns.

### Canonical pipeline (current)
1. `LoadOrCreateConversationStep`
2. `ResetConversationStep`
3. `PersistConversationBootstrapStep`
4. `AuditUserInputStep`
5. `PolicyEnforcementStep`
6. `IntentResolutionStep`
7. `ResetResolvedIntentStep`
8. `FallbackIntentStateStep`
9. `AddContainerDataStep`
10. `McpToolStep`
11. `SchemaExtractionStep`
12. `AutoAdvanceStep`
13. `RulesStep`
14. `ResponseResolutionStep`
15. `PersistConversationStep`
16. `PipelineEndGuardStep`

Order is enforced via step annotations (`@MustRunAfter`, `@MustRunBefore`, `@RequiresConversationPersisted`) and DAG validation.

## API Surface

### REST APIs
- `POST /api/v1/conversation/message`
- `GET /api/v1/conversation/audit/{conversationId}`
- `GET /api/v1/conversation/audit/{conversationId}/trace`
- `POST /api/v1/conversation/experimental/generate-sql` (feature-flagged)

### SSE stream API
- `GET /api/v1/conversation/stream/{conversationId}`
- Emits event name = audit stage (plus `CONNECTED` on subscribe).

### Typical stream stages
- `CONNECTED`
- `USER_INPUT`
- `STEP_ENTER`
- `STEP_EXIT`
- `STEP_ERROR`
- `ASSISTANT_OUTPUT`
- `ENGINE_RETURN`

## Data Model Contracts

### Non-transactional behavior tables
- `ce_config`
- `ce_container_config`
- `ce_intent`
- `ce_intent_classifier`
- `ce_output_schema`
- `ce_policy`
- `ce_prompt_template`
- `ce_response`
- `ce_rule`
- `ce_mcp_tool`
- `ce_mcp_db_tool`

### Runtime/transactional tables
- `ce_conversation`
- `ce_audit`
- `ce_conversation_history`
- `ce_llm_call_log`

### Important schema truths

`ce_prompt_template` uses:
- `template_id` (PK)
- `intent_code`
- `state_code`
- `response_type`
- `system_prompt`
- `user_prompt`
- `temperature`
- `enabled`

There is no `template_code` in current DDL.

`ce_response` uses:
- `response_id` (PK)
- `intent_code`
- `state_code`
- `output_format`
- `response_type`
- `exact_text`
- `derivation_hint`
- `json_schema`
- `priority`
- `enabled`

There is no `prompt_template_code` in current DDL.

## Enum / Value Matrix (Current)

This file is for contributors and internal agents working on ConvEngine.

## What ConvEngine Is

ConvEngine is a deterministic conversational orchestration engine.

- It is configuration-first (`ce_*` tables + app config).
- It is step-pipeline driven.
- It is audit-first by design.

Do not treat ConvEngine as an unconstrained chatbot runtime.

## Current Baseline

- Library version: `2.0.9`
- Property namespace for flow tuning: `convengine.flow.*`

## Core Operating Model

1. Resolve/maintain intent + state
2. Extract structured fields by schema
3. Compute transitions via rules and policy
4. Resolve output by exact/derived response mapping
5. Dispatch async background persistence (`ce_conversation`, `ce_llm_call_log`, `ce_conversation_history`) + publish audit timeline

## Source of Truth Order

When implementing behavior:

1. `src/main/resources/sql/ddl.sql` (schema truth)
2. `ce_*` table contracts (data behavior)
3. Step pipeline contracts
4. API/controller mapping
5. Docs

If docs/examples conflict with DDL, DDL wins.

## Must-Keep Contracts

### `ce_prompt_template`

Columns include:

- `template_id`, `intent_code`, `state_code`, `response_type`, `system_prompt`, `user_prompt`, `temperature`, `interaction_mode`, `interaction_contract`, `enabled`

`interaction_mode` is the coarse semantic bucket for the turn. Supported values:

- `NORMAL`, `IDLE`, `COLLECT`, `CONFIRM`, `PROCESSING`, `FINAL`, `ERROR`, `DISAMBIGUATE`, `FOLLOW_UP`, `PENDING_ACTION`, `REVIEW`

`interaction_contract` is JSON text used for extensible capabilities and expectations. Recommended shape:

- `{"allows":["affirm","edit","retry","reset"],"expects":["structured_input"]}`

No `template_code`.

### `ce_response`

Columns include:

- `response_id`, `intent_code`, `state_code`, `output_format`, `response_type`, `exact_text`, `derivation_hint`, `json_schema`, `priority`, `enabled`, `description`

No `prompt_template_code`.

### `ce_rule`

- `rule_type`: `EXACT | REGEX | JSON_PATH`
- `phase`: `PRE_RESPONSE_RESOLUTION | POST_AGENT_INTENT | POST_SCHEMA_EXTRACTION | PRE_AGENT_MCP | POST_AGENT_MCP | POST_TOOL_EXECUTION`
- `state_code`: `ANY | UNKNOWN | exact`
- `action`: `SET_INTENT | SET_STATE | SET_JSON | GET_CONTEXT | GET_SCHEMA_JSON | GET_SESSION | SET_TASK | SET_INPUT_PARAM`

### `ce_pending_action`

Catalog of action candidates by intent/state/action key.

Catalog of action candidates by intent/state/action key. 
When creating `CePendingAction` rows, ensure they are paired with a corresponding Tool/Task executing the logic, and ensure `InteractionPolicy` configuration accurately maps `PENDING_ACTION:AFFIRM` to execution.

Runtime lifecycle (`OPEN`, `IN_PROGRESS`, `EXECUTED`, `REJECTED`, `EXPIRED`) is maintained in context (`pending_action_runtime`), not in this table.

## Step Design Rules

- Keep steps composable and side-effect scoped.
- Prefer validation/audit in steps over hidden mutation.
- For safety steps (guardrail/state graph), fail-soft unless explicitly configured fail-closed.
- Keep order constraints explicit with annotations.

## Rule-First Philosophy

Before adding Java branching:

- check if behavior can be expressed via `ce_rule`
- check phase-specific rules (`POST_AGENT_INTENT`, `POST_SCHEMA_EXTRACTION`, `PRE_AGENT_MCP`, `POST_AGENT_MCP`, `POST_TOOL_EXECUTION`)
- check whether action can be `SET_TASK` or `SET_INPUT_PARAM`

## Audit Expectations

Any non-trivial decision should emit a stage event.

At minimum ensure visibility for:

- dialogue act
- interaction policy decision
- pending action lifecycle/skip/execute/reject/fail
- guardrail allow/deny
- disambiguation requirement
- state graph validation/violation
- tool orchestration request/result/error

## MCP + Tooling

Tool routing is by `tool_group` with executor adapters.
**CRITICAL**: All tools must use explicit scope values. Use `intent_code` / `state_code` with concrete values or `ANY` / `UNKNOWN`. Do not use null scope values.

Supported canonical groups:

- `DB`
- `HTTP_API`
- `WORKFLOW_ACTION`
- `DOCUMENT_RETRIEVAL`
- `CALCULATOR_TRANSFORM`
- `NOTIFICATION`
- `FILES`

Prefer adapters/interfaces; avoid hardcoding transport logic in steps.

### MCP extensions (v2.0.7)

- `DB` now supports per-tool handlers via `DbToolHandler` before fallback to `ce_mcp_db_tool.sql_template`.
- MCP planner prompts are now use-case scoped in `ce_mcp_planner` (`intent_code` + `state_code`) with legacy `ce_config` fallback.
- Optional built-in DB knowledge graph tool (`DbKnowledgeGraphToolHandler`) can read consumer-managed query/schema knowledge tables (`convengine.mcp.db.knowledge.*`) and return ranked semantic matches.
- `HTTP_API` now supports `HttpApiRequestingToolHandler` and framework-managed invocation via `HttpApiToolInvoker` for retries, backoff, circuit breaker, timeout, auth provider injection, and response mapping.

### CE verbose + stream envelope extensions (v2.0.8)

- New control-plane table `ce_verbose` drives user-facing runtime progress and error messaging by `intent_code`, `state_code`, `step_match`, `step_value`, and `determinant`.
- New standalone SQL assets:
  - `src/main/resources/sql/verbose_ddl.sql`
  - `src/main/resources/sql/verbose_seed.sql`
  plus merged rows in all dialect main `ddl_*` and `seed_*` files.
- `EngineSession` now carries `stepInfos` (`StepInfo`) with step enter/exit/error timings and outcomes; preserve this shape when extending session metadata.
- Streaming payload contract changed: `AuditStreamEventResponse` now includes `eventType` and optional `verbose` payload. Both SSE and STOMP can emit `AUDIT` and `VERBOSE`.
- MCP now supports deterministic schema-incomplete skip (`MCP_SKIPPED_SCHEMA_INCOMPLETE`, `STATUS_SKIPPED_SCHEMA_INCOMPLETE`) in `McpToolStep`.

### Prompt + correction extensions (v2.0.9)

- Prompt rendering is now shared through `ThymeleafTemplateRenderer`; use Thymeleaf text expressions in prompts and `ce_verbose` messages when you need conditional/default rendering. Legacy `{{var}}` forms still work.
- `PromptTemplateContext` and session prompt vars now include `standalone_query` and `resolved_user_input`.
- `DialogueAct` now includes `ANSWER`.
- `DialogueActStep` now audits `DIALOGUE_ACT_LLM_INPUT`, `DIALOGUE_ACT_LLM_OUTPUT`, and `DIALOGUE_ACT_LLM_ERROR` whenever the LLM path is used.
- `DialogueActStep` now exposes regex and LLM candidate dialogue-act values in session input params and runs `POST_DIALOGUE_ACT` rules before `InteractionPolicyStep`.
- `CorrectionStep` runs before intent/schema resolution and can route confirmation affirmations forward without re-extracting schema and patch single-field confirmation edits in-place.
- Use `routing_decision` (not `turn_mode`) for downstream flow routing.
- Consumers can emit UI verbose events directly through `ConvEngineVerboseAdapter` from hooks, transformers, and custom beans.

## Documentation Discipline

When behavior changes:

1. Update `README.md`
2. Update this `AGENT.md`
3. Update `src/main/resources/prompts/SQL_GENERATION_AGENT.md` if SQL contracts changed
4. Keep examples runnable and enum-accurate

## Framework Performance Patterns (v2.0.7+)

Do not introduce synchronous Relational DB reads/writes into the core engine lifecycle path:
- **Static Configuration**: All `ce_*` configuration tables must be resolved via the `StaticConfigurationCacheService` interface, NOT direct JpaRepository `.find()` hits. The application strictly maintains a 0-latency pre-loaded RAM cache.
- **Transactional State**: The primary `ce_conversation` mutation must be isolated via `ConversationCacheService` using Spring Cache mechanisms. Never call `conversationRepository.save()` sequentially on the primary NLP thread.
- **Async Execution**: Delegate all database `INSERT` commands to parallel executor methods (e.g. `AsyncConversationPersistenceService`, `AsyncLlmCallLogPersistenceService`, `AsyncConversationHistoryPersistenceService`) firing in fire-and-forget topologies.

## Release Hygiene Checklist

- DDL/seed aligned
- Rule phase enum aligned across code/docs
- Dialogue-act post-processing rule phase and action enum aligned across code/docs
- New input param keys centralized in constants
- New audit stages centralized in enum
- `ce_verbose` docs/examples aligned with actual emitted determinants
- No stale config prefixes in docs (`convengine.flow.*` only)
- `ce_verbose` rows validated for `step_match` (`EXACT|REGEX|JSON_PATH`) and non-empty `step_value`/`determinant`
- Prompt and verbose rendering changes should reuse `ThymeleafTemplateRenderer`; do not add parallel ad hoc renderers
