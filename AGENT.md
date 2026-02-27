# ConvEngine - Agent Guide

This file is for contributors and internal agents working on ConvEngine.

## What ConvEngine Is

ConvEngine is a deterministic conversational orchestration engine.

- It is configuration-first (`ce_*` tables + app config).
- It is step-pipeline driven.
- It is audit-first by design.

Do not treat ConvEngine as an unconstrained chatbot runtime.

## Current Baseline

- Library version: `2.0.8`
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

- `template_id`, `intent_code`, `state_code`, `response_type`, `system_prompt`, `user_prompt`, `temperature`, `enabled`

No `template_code`.

### `ce_response`

Columns include:

- `response_id`, `intent_code`, `state_code`, `output_format`, `response_type`, `exact_text`, `derivation_hint`, `json_schema`, `priority`, `enabled`, `description`

No `prompt_template_code`.

### `ce_rule`

- `rule_type`: `EXACT | REGEX | JSON_PATH`
- `phase`: `PRE_RESPONSE_RESOLUTION | POST_AGENT_INTENT | POST_AGENT_MCP | POST_TOOL_EXECUTION`
- `state_code`: `ANY | UNKNOWN | exact`

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
- check phase-specific rules (`POST_AGENT_INTENT`, `POST_AGENT_MCP`, `POST_TOOL_EXECUTION`)
- check whether action can be `SET_TASK`

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
- New input param keys centralized in constants
- New audit stages centralized in enum
- No stale config prefixes in docs (`convengine.flow.*` only)
- `ce_verbose` rows validated for `step_match` (`EXACT|REGEX|JSON_PATH`) and non-empty `step_value`/`determinant`
