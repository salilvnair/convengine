# ConvEngine Framework - AGENT Guide

This file is a high-context orientation guide for humans and LLM agents working inside the `convengine` repository.

It is intentionally detailed. The goal is that an agent can read this file first and immediately understand:

- what the framework is
- what the framework is not
- how runtime behavior is assembled
- which code and SQL files matter most
- which tables, phases, and steps are contractual
- which release-line changes are already in the framework
- how to make safe changes without breaking consumer integrations

If this file conflicts with code, the code wins. If docs conflict with SQL DDL, the DDL and actual entity model win.

## 1. What This Repository Is

`convengine` is a Java framework library for deterministic, database-configured conversational workflows.

This is not a generic chat app and not a "prompt-only agent" project.

The framework is built around:

- a fixed but extensible step pipeline
- database control-plane tables (`ce_*`)
- scoped, auditable intent/state transitions
- deterministic runtime metadata
- consumer-provided infrastructure (LLM client, datasource, custom tools/hooks)

The framework is meant for:

- enterprise workflow orchestration
- auditable multi-turn state machines
- schema-driven data capture
- confirmation and pending-action flows
- tool use with scoped eligibility
- supportable operational debugging through audit and verbose traces

The framework is not meant for:

- unconstrained assistant chat
- hidden state mutation by model output alone
- "just let the LLM decide everything" architectures

## 2. Current Release Baseline

### Current library version

- `2.0.9`

### Java / framework baseline

- Java 21
- Spring Boot ecosystem
- JPA/Hibernate
- Thymeleaf text rendering
- JSON Path
- Spring cache support
- Spring async support
- optional SSE + STOMP transports

### Core namespaces to know first

- `com.github.salilvnair.convengine.engine.*`
- `com.github.salilvnair.convengine.audit.*`
- `com.github.salilvnair.convengine.cache.*`
- `com.github.salilvnair.convengine.entity.*`
- `com.github.salilvnair.convengine.repo.*`
- `com.github.salilvnair.convengine.config.*`
- `com.github.salilvnair.convengine.transport.*`
- `com.github.salilvnair.convengine.prompt.*`
- `com.github.salilvnair.convengine.intent.*`
- `com.github.salilvnair.convengine.llm.*`

## 3. Mental Model: How ConvEngine Actually Works

The runtime is a pipeline-driven turn engine.

At a high level, one request does this:

1. Load or create the conversation state.
2. Audit the user turn.
3. Determine dialogue-act and deterministic routing implications.
4. Resolve / preserve / adjust intent and state.
5. Optionally run pending-action logic, direct tool logic, or planner-driven MCP logic.
6. Extract structured schema fields.
7. Run rule phases that may mutate state, intent, task, JSON, or input params.
8. Validate state transitions.
9. Resolve the final response payload.
10. Update memory.
11. Persist conversation output and emit trace/audit/verbose data.

The "brain" of the system is not one class. It is the combination of:

- step ordering
- `ce_*` rows
- rule phases
- scoped response/template selection
- consumer-provided integrations

## 4. Source of Truth Order

When you are trying to understand or modify behavior, trust sources in this order:

1. `src/main/resources/sql/ddl.sql` and dialect DDL files
2. entity classes in `src/main/java/.../entity`
3. step pipeline and runtime code
4. controller / API DTOs
5. docs in `convengine-docs`

Corollary:

- if docs say a column is nullable but DDL says `NOT NULL`, the DDL is correct
- if an old example mentions a legacy phase name, `RulePhase.normalize(...)` tells you current runtime truth
- if an example flow contradicts a step class, the step class is the real behavior

## 5. Repository Structure Map

This repo is a framework library, not a monolith app.

Top-level areas that matter:

- `src/main/java/com/github/salilvnair/convengine/annotation`
  - opt-in annotations like `@EnableConvEngine`, `@EnableConvEngineCaching`, `@EnableConvEngineAsyncConversation`
- `src/main/java/com/github/salilvnair/convengine/api`
  - REST controllers and DTOs exposed by the framework
- `src/main/java/com/github/salilvnair/convengine/audit`
  - audit stage emission, persistence, dispatch, trace assembly
- `src/main/java/com/github/salilvnair/convengine/cache`
  - static config caches, runtime caches, analyzers, warmers, validators
- `src/main/java/com/github/salilvnair/convengine/config`
  - `@ConfigurationProperties`, auto-config, transport config, audit config
- `src/main/java/com/github/salilvnair/convengine/container`
  - consumer container-data fetch/enrichment support
- `src/main/java/com/github/salilvnair/convengine/engine`
  - the core pipeline, steps, rule execution, schema logic, MCP, memory
- `src/main/java/com/github/salilvnair/convengine/entity`
  - JPA entities representing `ce_*` tables
- `src/main/java/com/github/salilvnair/convengine/experimental`
  - optional experimental SQL generation features
- `src/main/java/com/github/salilvnair/convengine/intent`
  - classifier + agent-based intent resolution
- `src/main/java/com/github/salilvnair/convengine/llm`
  - LLM abstraction layer
- `src/main/java/com/github/salilvnair/convengine/prompt`
  - prompt variable context and rendering
- `src/main/java/com/github/salilvnair/convengine/repo`
  - JPA repositories
- `src/main/java/com/github/salilvnair/convengine/transport`
  - SSE, STOMP, verbose transport objects
- `src/main/resources/sql`
  - DDL, seeds, standalone verbose SQL, planner seeds
- `src/main/resources/prompts`
  - prompt assets including SQL generation helper prompt docs

## 6. Public Runtime APIs

Framework-exposed endpoints:

- `POST /api/v1/conversation/message`
- `GET /api/v1/conversation/audit/{conversationId}`
- `GET /api/v1/conversation/audit/{conversationId}/trace`
- `GET /api/v1/conversation/stream/{conversationId}`
- `POST /api/v1/cache/refresh`
- `GET /api/v1/cache/analyze`

Feature-flagged experimental endpoints:

- `POST /api/v1/conversation/experimental/generate-sql`
- `POST /api/v1/conversation/experimental/generate-sql/zip`

## 7. Runtime Pipeline Contract

Step ordering is DAG-resolved in `EnginePipelineFactory`.

The order is not handwritten in one list. It is derived from:

- `@MustRunBefore`
- `@MustRunAfter`
- `@RequiresConversationPersisted`
- `@ConversationBootstrapStep`
- `@TerminalStep`

### Canonical step set to know

- `LoadOrCreateConversationStep`
- `CacheInspectAuditStep`
- `ResetConversationStep`
- `PersistConversationBootstrapStep`
- `AuditUserInputStep`
- `PolicyEnforcementStep`
- `DialogueActStep`
- `InteractionPolicyStep`
- `CorrectionStep`
- `ActionLifecycleStep`
- `DisambiguationStep`
- `GuardrailStep`
- `IntentResolutionStep`
- `ResetResolvedIntentStep`
- `FallbackIntentStateStep`
- `AddContainerDataStep`
- `PendingActionStep`
- `ToolOrchestrationStep`
- `McpToolStep`
- `SchemaExtractionStep`
- `AutoAdvanceStep`
- `RulesStep`
- `StateGraphStep`
- `ResponseResolutionStep`
- `MemoryStep`
- `PersistConversationStep`
- `PipelineEndGuardStep`

## 8. Core Control-Plane Tables

### Core conversation behavior

- `ce_config`
- `ce_policy`
- `ce_intent`
- `ce_intent_classifier`
- `ce_output_schema`
- `ce_prompt_template`
- `ce_response`
- `ce_rule`
- `ce_container_config`

### Tooling / MCP / pending actions / verbose

- `ce_pending_action`
- `ce_mcp_tool`
- `ce_mcp_planner`
- `ce_mcp_db_tool`
- `ce_verbose`

### Runtime / transactional

- `ce_conversation`
- `ce_audit`
- `ce_conversation_history`
- `ce_llm_call_log`
- `ce_validation_snapshot`

## 9. Most Important Table Contracts

### `ce_prompt_template`

This table is both prompt text and runtime behavior metadata.

Important columns:

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

Important truths:

- there is no `template_code`
- there is no `prompt_template_code` link from `ce_response`
- `interaction_mode` is a semantic marker for turn type
- `interaction_contract` is extensible JSON and should be used instead of new one-off columns

### `ce_response`

This is the final response mapping authority.

Important columns:

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

### `ce_rule`

Current `rule_type` values:

- `EXACT`
- `REGEX`
- `JSON_PATH`

Current `phase` values:

- `POST_DIALOGUE_ACT`
- `POST_SCHEMA_EXTRACTION`
- `PRE_AGENT_MCP`
- `PRE_RESPONSE_RESOLUTION`
- `POST_AGENT_INTENT`
- `POST_AGENT_MCP`
- `POST_TOOL_EXECUTION`

Current `action` values:

- `SET_INTENT`
- `SET_STATE`
- `SET_DIALOGUE_ACT`
- `SET_JSON`
- `GET_CONTEXT`
- `GET_SCHEMA_JSON`
- `GET_SESSION`
- `SET_TASK`
- `SET_INPUT_PARAM`

### `ce_pending_action`

This is the static catalog of pending actions.

Important truths:

- current scope must be explicit and valid
- runtime lifecycle is stored in `pending_action_runtime` in context, not in this table

### `ce_mcp_tool`

Critical current truth:

- `intent_code` is mandatory
- `state_code` is mandatory
- null wildcard semantics are obsolete
- use exact scope, `ANY`, or `UNKNOWN`

### `ce_mcp_planner`

Critical current truth:

- it is now first-class runtime config
- it is scoped like `ce_mcp_tool`

### `ce_verbose`

This is the current control-plane table for runtime progress and error messaging.

`step_match` must be one of:

- `EXACT`
- `REGEX`
- `JSON_PATH`

## 10. Runtime Configuration Properties

The main property namespaces are:

- `convengine.flow.*`
- `convengine.audit.*`
- `convengine.transport.*`
- `convengine.mcp.*`
- `convengine.experimental.*`

## 11. MCP / Tool Architecture

There are two distinct tool paths in the framework.

### Direct tool path

Handled by `ToolOrchestrationStep`.

Writes:

- `tool_result`
- `tool_status`
- `context.mcp.toolExecution.*`

Runs:

- `POST_TOOL_EXECUTION` rules

### Planner-driven MCP path

Handled by `McpToolStep`.

Current behavior:

- `PRE_AGENT_MCP` rules can run before the planner
- observations accumulate in `context.mcp.observations`
- final planner text is stored in `context.mcp.finalAnswer`
- `POST_AGENT_MCP` rules run after MCP completion / block / fallback

### Supported canonical tool groups

- `DB`
- `HTTP_API`
- `WORKFLOW_ACTION`
- `DOCUMENT_RETRIEVAL`
- `CALCULATOR_TRANSFORM`
- `NOTIFICATION`
- `FILES`

## 12. Prompt and Rendering Model

Current rendering path is shared via:

- `ThymeleafTemplateRenderer`

Supported styles:

- legacy `{{var}}`
- `#{...}`
- `[${...}]`

The same rendering path is used by:

- prompt templates
- `ce_verbose.message`
- `ce_verbose.error_message`

## 13. Audit, Trace, and Verbose Model

Audit is the supportability backbone of the framework.

At minimum, preserve visibility for:

- dialogue-act classification
- interaction policy decisions
- correction routing
- pending action lifecycle
- guardrail allow/deny
- tool orchestration request/result/error
- MCP planning / tool call / final answer
- rules matching and application
- response selection

`EngineSession.stepInfos` is part of the current runtime shape and should remain consistent.

## 14. Performance and Cache Rules

ConvEngine is designed to avoid repeated hot-path SQL reads for static config.

Must-follow rules:

- resolve static config through `StaticConfigurationCacheService`
- do not add direct hot-path repository access when a cache helper exists
- do not add blocking persistence to the critical response path when async paths exist

Key cache surfaces:

- static config cache
- conversation cache
- conversation history cache

Key operational endpoints:

- `/api/v1/cache/refresh`
- `/api/v1/cache/analyze`

## 15. Consumer Extension Points

Important extension surfaces:

- `LlmClient`
- tool handlers / executors
- `CeTaskExecutor`
- response transformers
- container transformers
- `ConvEngineVerboseAdapter`

Prefer extension interfaces and scoped handlers over framework-level switch blocks.

## 16. Release-Line Snapshot (Mirrors `convengine-docs` Version History)

### `2.0.9`

- shared Thymeleaf-backed prompt rendering
- `standalone_query` and `resolved_user_input`
- `SET_INPUT_PARAM`
- `POST_SCHEMA_EXTRACTION`
- `PRE_AGENT_MCP`
- `DialogueAct.ANSWER`
- `CorrectionStep`
- richer LLM verbose coverage
- `ConvEngineVerboseAdapter`

### `2.0.8`

- `ce_verbose`
- `VerboseStreamPayload`
- `EngineSession.stepInfos`
- `AUDIT` + `VERBOSE` stream envelopes
- deterministic MCP schema-incomplete skip

### `2.0.7`

- `ce_mcp_planner`
- stricter static scope validation for MCP
- richer MCP lifecycle metadata
- advanced HTTP tool handler models
- DB handler-first MCP path
- normalized modern rule phase names

### `2.0.6`

- cache proxy hygiene
- `ConvEngineCacheAnalyzer`
- `/api/v1/cache/analyze`

### `2.0.5`

- static-table preloading
- `/api/v1/cache/refresh`
- async persistence and query rewrite improvements

### `2.0.4`

- time serialization stability fixes

### `2.0.3`

- cache inspection auditing
- improved history provisioning

### `2.0.2`

- deeper performance optimizations
- contextual query rewrite

### `2.0.1`

- prompt audit metadata upgrades

### `2.0.0`

- pending actions
- dialogue-act routing
- deterministic interaction policies
- MCP tool orchestration
- guardrails
- state graph validation
- memory and replay patterns

## 17. Safe Change Rules for Agents

When editing this repo:

- do not casually change DB string contracts used by configuration rows
- do not change DDL semantics without updating entities, seeds, docs, and validators
- do not loosen MCP scope rules back toward nullable wildcard behavior
- do not hide major runtime decisions from audit/verbose output
- do not bypass caches for static config unless there is a documented reason
- do not move behavior into Java if a data-driven table contract is the right location

## 18. Required Companion Updates When Behavior Changes

If you change runtime behavior, update these in the same workstream:

1. SQL DDL / seed files in `src/main/resources/sql`
2. entities and repositories
3. startup validators / cache preloaders if table semantics changed
4. docs in `convengine-docs`
5. this `AGENT.md`
6. `src/main/resources/prompts/SQL_GENERATION_AGENT.md` if SQL contract generation changed

## 19. Best First Files to Read

1. `src/main/resources/sql/ddl.sql`
2. `src/main/java/com/github/salilvnair/convengine/engine/factory/EnginePipelineFactory.java`
3. `src/main/java/com/github/salilvnair/convengine/engine/pipeline/EngineStep.java`
4. `src/main/java/com/github/salilvnair/convengine/engine/type/RulePhase.java`
5. `src/main/java/com/github/salilvnair/convengine/engine/steps/*`
6. `src/main/java/com/github/salilvnair/convengine/cache/StaticConfigurationCacheService.java`
7. `src/main/java/com/github/salilvnair/convengine/cache/StaticScopeIntegrityValidator.java`
8. `src/main/java/com/github/salilvnair/convengine/api/controller/ConversationController.java`

## 20. One-Sentence Operating Rule

ConvEngine is a deterministic workflow engine whose behavior is co-authored by pipeline code, scoped database configuration, and auditable runtime metadata; safe changes preserve all three together.
