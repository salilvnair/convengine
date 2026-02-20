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

### `ce_response`
- `response_type`: `EXACT | DERIVED`
- `output_format`: `TEXT | JSON`

### `ce_prompt_template`
- `response_type`: `TEXT | JSON | SCHEMA_JSON`

### `ce_rule`
- `rule_type`: `EXACT | REGEX | JSON_PATH`
- `state_code`: `NULL` (all states), `ANY` (all states), or a specific `state_code`
- `action`: `SET_INTENT | SET_STATE | SET_JSON | GET_CONTEXT | GET_SCHEMA_JSON | GET_SESSION | SET_TASK`

### `ce_intent_classifier`
- `rule_type`: `REGEX | CONTAINS | STARTS_WITH`

## Session and State Contracts

`EngineSession` is the live mutable runtime envelope. Core fields:
- `intent`, `state`
- `contextJson`
- schema status (`resolvedSchema`, `schemaComplete`, `schemaHasAnyValue`)
- clarification state (`pendingClarificationQuestion`, etc)
- `inputParams` with controlled exposure
- `payload` and final `EngineResult`

### Intent lock behavior
- Incomplete schema collection can lock intent to prevent reclassification drift.
- Lock state is persisted/restored via context.

### Controlled prompt vars
- Prompt resolvers use `promptTemplateVars()` and safe filters.
- System-derived unsafe keys are not blindly exposed to prompt templates.

## Reset Semantics

### Input/message based reset
Reset can be triggered by:
- request `reset=true`
- input params `reset=true | restart=true | conversation_reset=true`
- user message commands (`reset`, `restart`, `/reset`, `/restart`)

### Intent-based reset via config
`ResetResolvedIntentStep` supports reset intent codes from `ce_config`:
- `config_type='ResetResolvedIntentStep'`
- `config_key='RESET_INTENT_CODES'`
- default includes `RESET_SESSION`

Example:
```sql
INSERT INTO ce_config (config_type, config_key, config_value, enabled)
VALUES ('ResetResolvedIntentStep', 'RESET_INTENT_CODES', 'RESET_SESSION,START_OVER', true);
```

## Rule Engine Contracts

`RulesStep` loads enabled rules ordered by priority and applies multi-pass execution (up to bounded passes) when intent/state changes.

State scoping contract for `ce_rule.state_code`:
- `NULL` -> rule is eligible for all states.
- `ANY` -> rule is eligible for all states.
- Specific value -> rule is eligible only when session state equals that value (case-insensitive).

### Action value formats
- `SET_TASK`: `beanName:methodName` or `beanName:methodA,methodB`
- `SET_JSON`: `targetKey:jsonPath`
- `GET_CONTEXT`: `targetKey` (optional, default `context`)
- `GET_SESSION`: `targetKey` (optional, default `session`)

### `SET_TASK` runtime
- Action is resolved by `SetTaskActionResolver`.
- Task invocation is delegated to `CeRuleTaskExecutor`.
- Target bean must be a Spring bean implementing `CeRuleTask`.
- Method signature must accept `(EngineSession, CeRule)`.

### Custom rule actions
Consumers can add custom actions by creating a Spring bean implementing `RuleActionResolver`.  
`RuleActionResolverFactory` auto-discovers these resolvers by `action().toUpperCase()`.

## Extension Points (Consumer)

### Step hooks
`EngineStepHook` supports typed and compatibility signatures:
- `supports(EngineStep.Name stepName, EngineSession session)`
- `supports(String stepName, EngineSession session)`
- `beforeStep(...)`, `afterStep(...)`, `onStepError(...)`

Use `EngineStep.Name` enum matching where possible.

### Output and container interception
- `@ResponseTransformer` + `ResponseTransformerHandler`
- `@ContainerDataTransformer` + handler
- `@ContainerDataInterceptor`

These are intended for consumer-specific intervention without forking core engine steps.

## Audit and Trace Model

### Core behavior
- `ce_audit` is the source of truth for execution timeline.
- `ce_conversation_history` is the source for conversation-turn reconstruction used by prompt history providers.
- Step lifecycle emits `STEP_ENTER`, `STEP_EXIT`, `STEP_ERROR`.
- Audit metadata should track runtime session `intent` and `state`.
- `_meta` persistence in DB payloads is controlled by `convengine.audit.persist-meta`.
- `ce_conversation_history` writes are synchronous; `ce_audit` persistence can run in `IMMEDIATE` or `DEFERRED_BULK` strategy mode.

### Trace API
- `GET /api/v1/conversation/audit/{conversationId}/trace`
- Reconstructs ordered step timeline and stage events from audit rows.

### Audit controls (`convengine.audit.*`)
- enable/disable + level filtering (`ALL`, `STANDARD`, `ERROR_ONLY`, `NONE`)
- include/exclude stage filters
- async dispatch with bounded queue + rejection policy
- rate limiting
- persistence mode:
  - `IMMEDIATE`
  - `DEFERRED_BULK` with flush conditions

### Configuration ownership
- ConvEngine does not ship framework-level `application.yaml` defaults.
- Consumer applications own all `convengine.*` property configuration.

## Streaming and Transport

### Enable/disable behavior
- `@EnableConvEngine(stream = true)`:
  - validates that at least one transport is enabled (`sse` or `stomp`)
  - fails startup if both are disabled
- `@EnableConvEngine(stream = false)`:
  - stream conditions are bypassed
  - REST-only behavior remains active

### Transport properties
- `convengine.transport.sse.*`
- `convengine.transport.stomp.*`
- STOMP broker mode:
  - `SIMPLE` (in-memory)
  - `RELAY` (external broker relay)

## Experimental SQL Generation

- Feature flag: `convengine.experimental.enabled=true`
- Endpoint: `POST /api/v1/conversation/experimental/generate-sql`
- Prompt knowledge source:
  - `src/main/resources/prompts/SQL_GENERATION_AGENT.md`
- Generates non-transactional `ce_*` inserts only.
- Excludes runtime transactional tables.

## Engineering Guidance

1. Prefer DB config changes before Java code changes.
2. Keep step logic domain-agnostic and composable.
3. Keep transitions explicit in `ce_rule`; avoid hidden branching.
4. Validate behavior via audit trace before production rollout.
5. Keep prompt inputs controlled; avoid uncontrolled param injection.
6. When updating docs/examples, align SQL strictly with `src/main/resources/sql/ddl.sql`.
