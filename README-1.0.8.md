# ConvEngine 1.0.8 Release README

## Scope
This document summarizes all Java-level changes added for `1.0.8`, including new classes, new fields, new methods, changed behavior, API additions, and configuration knobs.

## Version And Dependencies
1. `pom.xml`
- Version bumped from `1.0.7` to `1.0.8`.
- Added dependencies to support streaming transports:
- `org.springframework:spring-webmvc`
- `org.springframework:spring-websocket`
- `org.springframework:spring-messaging`

## High-Level Engine Changes
1. Added step-level lifecycle auditing (`STEP_ENTER`, `STEP_EXIT`, `STEP_ERROR`) with intent/state in audit `_meta`.
2. Added consumer step hook contract (`EngineStepHook`) so consumers can intervene before/after/error for any step.
3. Added reset capabilities in two modes:
- User/input-driven reset (`reset`, `restart`, `conversation_reset`, message command reset/restart).
- Intent-driven reset via configurable `ce_config` key `RESET_INTENT_CODES` (default `RESET_SESSION`).
4. Added audit trace API to return step-oriented execution tree view.
5. Added streaming transports for audit events over SSE and STOMP (configurable).
6. Added experimental SQL generation API (feature-flagged) that asks LLM to produce `ce_*` INSERT scripts.
7. Hardened input parameter handling in `EngineSession` to avoid uncontrolled prompt variables.
8. Added intent locking lifecycle so incomplete schema collection keeps previous intent and avoids re-resolving intent.

## Detailed Class-Level Changes

### 1) API Layer
1. `src/main/java/com/github/salilvnair/convengine/api/dto/ConversationRequest.java`
- Added field: `private Boolean reset;`
- Purpose: lets API caller explicitly request conversation reset in `/message` request.

2. `src/main/java/com/github/salilvnair/convengine/api/controller/ConversationController.java`
- Added dependency field: `private final AuditTraceService auditTraceService;`
- Updated `message(...)`:
- Builds a local `inputParams` map (copy of request params).
- If `reset == true`, injects `inputParams.put("reset", true)`.
- Ensures reset can be handled uniformly by step pipeline logic.
- Added endpoint: `GET /api/v1/conversation/audit/{conversationId}/trace`
- Method: `getAuditTrace(UUID conversationId)`
- Returns: `AuditTraceResponse` (step + stage oriented audit trace view).

3. `src/main/java/com/github/salilvnair/convengine/api/controller/ExperimentalController.java` (new)
- Base path: `/api/v1/conversation/experimental`
- Guarded by: `@ConditionalOnProperty(prefix = "convengine.experimental", name = "enabled", havingValue = "true")`
- Added endpoint: `POST /generate-sql`
- Delegates to `ExperimentalSqlGenerationService.generate(...)`.

4. New DTOs for trace/stream/experimental output
- `AuditTraceResponse`
- Fields: `conversationId`, `steps`, `stages`
- `AuditStepTraceResponse`
- Fields: `step`, `stepClass`, `sourceFile`, `status`, `startedAt`, `endedAt`, `durationMs`, `error`, `stages`
- `AuditStageTraceResponse`
- Fields: `auditId`, `stage`, `createdAt`, `payload`
- `AuditStreamEventResponse`
- Fields: `auditId`, `stage`, `createdAt`, `payload`
- `ExperimentalSqlGenerationRequest`
- Fields: `scenario`, `domain`, `constraints`, `includeMcp` (default `true`)
- `ExperimentalSqlGenerationResponse`
- Fields: `success`, `sql`, `warnings`, `note`

### 2) Audit Infrastructure
1. `src/main/java/com/github/salilvnair/convengine/audit/AuditEventListener.java` (new)
- Contract: `void onAudit(CeAudit audit);`
- Purpose: pluggable fan-out of audit events (SSE, STOMP, future transports).

2. `src/main/java/com/github/salilvnair/convengine/audit/AuditPayloadMapper.java` (new)
- Utility method: `payloadAsMap(String payloadJson)`
- Behavior:
- Parses payload JSON safely into map.
- If payload is scalar/non-object, wraps in `{ "value": ... }`.
- On parse failure, returns `{ "raw_payload": ... }`.

3. `src/main/java/com/github/salilvnair/convengine/audit/AuditSessionContext.java` (new)
- ThreadLocal holder for current `EngineSession`.
- Methods: `set(...)`, `get()`, `clear()`.
- Purpose: allow audit layer to read runtime session intent/state reliably while steps execute.

4. `src/main/java/com/github/salilvnair/convengine/audit/DbAuditService.java`
- Added dependencies:
- `ConversationRepository conversationRepository`
- `List<AuditEventListener> listeners`
- Replaced payload path from `sanitizeJson(...)` to `normalizePayload(...)`.
- `normalizePayload(...)` behavior:
- Always emits JSON object.
- Ensures `_meta` object contains `stage`, `conversationId`, `emittedAt`.
- Adds `_meta.intent` and `_meta.state` resolved in priority order:
- from current `AuditSessionContext` (runtime `EngineSession`)
- from payload fields (`intent`/`state` or `_meta.intent`/`_meta.state`)
- from `ce_conversation` row
- Handles invalid JSON payloads via fallback envelope.
- `saveAuditRecord(...)` now returns persisted `CeAudit`.
- Added `publish(CeAudit)` to notify all `AuditEventListener` implementations.
- Added helpers: `resolveIntent(...)`, `resolveState(...)`, `readText(...)`.

5. `src/main/java/com/github/salilvnair/convengine/audit/AuditTraceService.java` (new)
- Builds execution trace from ordered audit rows.
- Method: `trace(UUID conversationId)`:
- Loads audits.
- Builds flat stage list.
- Builds step stack tree via `STEP_ENTER` / `STEP_EXIT` / `STEP_ERROR`.
- Returns `AuditTraceResponse` with per-step duration/status/error plus stage history.
- Helper methods: `findAndPopStep(...)`, `toIso(...)`, `asLong(...)`, `str(...)`, `payloadAsMap(...)`.

### 3) Pipeline, Hooks, And Step Lifecycle
1. `src/main/java/com/github/salilvnair/convengine/engine/hook/EngineStepHook.java` (new)
- Methods:
- `supports(String stepName, EngineSession session)`
- `beforeStep(...)`
- `afterStep(...)`
- `onStepError(...)`
- All default no-op implementations.
- Purpose: consumer extension point to mutate session and influence engine between steps.

2. `src/main/java/com/github/salilvnair/convengine/engine/factory/EnginePipelineFactory.java`
- Added dependencies:
- `List<EngineStepHook> stepHooks`
- `AuditService audit`
- Timing wrapper upgraded to invoke hooks and lifecycle audit:
- Emits `STEP_ENTER` before delegate execution.
- Calls hook `beforeStep` for matching hooks.
- On success:
- Calls hook `afterStep`.
- Emits `STEP_EXIT` with step class and duration.
- On failure:
- Calls hook `onStepError`.
- Emits `STEP_ERROR` with class/type/message/duration.
- Hook failures do not fail engine step:
- `runHookSafely(...)` catches hook exceptions.
- Emits `STEP_HOOK_ERROR` audit with hook class and phase.
- Added `stepAuditPayload(...)` helper:
- Injects `step`, `stepClass`, extra fields.
- Adds nested `_meta` with session `intent` and `state`.
- Uses `AuditSessionContext.set(session)`/`clear()` around step execution for reliable audit metadata.

### 4) Session Bootstrap And Persistence Safety
1. `src/main/java/com/github/salilvnair/convengine/engine/factory/EngineSessionFactory.java`
- Added `ConversationRepository` dependency.
- `open(EngineContext)` now:
- Creates `EngineSession`.
- Ensures `ce_conversation` exists (`ensureConversationBootstrap`).
- Attaches conversation to session.
- Syncs session from conversation row.
- Added `ensureConversationBootstrap(UUID)` and `createMinimalConversation(UUID)`.
- Minimal row defaults:
- `status=RUNNING`, `stateCode=UNKNOWN`, empty `contextJson`/`inputParamsJson`, timestamps.
- Handles concurrent creation via `DataIntegrityViolationException` retry-read.

### 5) EngineSession Hardening And Reset Semantics
1. `src/main/java/com/github/salilvnair/convengine/engine/session/EngineSession.java`

New fields:
- `boolean intentLocked`
- `String intentLockReason`
- `Map<String,Object> systemExtensions`
- `Set<String> unknownSystemInputParamKeys`
- `Set<String> systemDerivedInputParamKeys`
- `CONTROLLED_PROMPT_KEYS` whitelist for known framework-managed prompt vars.
- `SAFE_INPUT_KEY_PATTERN` for exposable prompt key names.
- `RESET_CONTROL_KEYS` for reset control key filtering.

Input parameter control changes:
- Replaced old `initializeInputParams(...)` with:
- `mergeInputParams(Map<String,Object>, boolean overwrite, boolean fromSystemWrite)`
- `mergeInputParam(String, Object, boolean, boolean)`
- `putInputParam(...)` now routes through controlled merge path and marks system-derived keys.
- Added `promptTemplateVars()`:
- Returns only safe/exposable keys for prompt rendering.
- Excludes system-derived unknown keys and keys prefixed with `_`.
- Added `isExposablePromptVar(...)` and key-pattern validation.
- `safeInputParams()` now includes `droppedSystemPromptVars` when unknown system keys are filtered.

Intent lock persistence:
- Added `persistIntentLockToContext()`.
- Added `restoreIntentLockFromContext()`.
- Context now stores `intent_lock: { locked, reason }`.

Sync changes:
- `syncFromConversation()` now delegates to `syncFromConversation(boolean preserveContext)` then input param sync.
- New overload `syncFromConversation(boolean preserveContext)` allows preserving in-memory context when needed.

Prompt variable assembly:
- `addPromptTemplateVars()` now uses internal `inputParams` map directly for controlled defaults.

Intent lock APIs:
- `lockIntent(String reason)`
- `unlockIntent()`

Conversation reset API:
- `resetForConversationRestart()` clears engine/session runtime state to baseline and rehydrates non-reset request params.
- Clears intent/state/context, schema state, payload/container artifacts, pending clarification, validations, final result, lock state, and system-derived maps.
- Filters out control keys `reset`, `restart`, `conversation_reset` while rebuilding request params.

Session dictionary expansion:
- `sessionDict()` now includes `intentLocked` and `intentLockReason`.

### 6) Intent Resolution And Schema Interaction
1. `src/main/java/com/github/salilvnair/convengine/engine/steps/IntentResolutionStep.java`
- `INTENT_RESOLVE_START` audit now includes:
- `intentLocked`
- `intentLockReason`
- Skip condition expanded:
- If `session.isIntentLocked()` OR active schema collection.
- If schema collection is active and lock not set, lock intent with reason `SCHEMA_INCOMPLETE`.
- `INTENT_RESOLVE_SKIPPED_SCHEMA_COLLECTION` audit now includes lock metadata.
- Net effect: avoid repeated classifier/agent intent re-resolution while schema is incomplete.

2. `src/main/java/com/github/salilvnair/convengine/engine/steps/SchemaExtractionStep.java`
- If no schema found for intent, explicitly `unlockIntent()`.
- Uses `session.syncFromConversation(true)` to preserve current context in this phase.
- Prompt building now uses `session.promptTemplateVars()` (controlled vars only).
- After extraction:
- If schema complete -> `unlockIntent()`.
- Else -> `lockIntent("SCHEMA_INCOMPLETE")`.
- `SCHEMA_STATUS` audit now includes `intentLocked` and `intentLockReason`.
- `ensurePromptVariableDefaults(...)` now uses `session.putInputParam(...)` instead of raw map mutation.

3. `src/main/java/com/github/salilvnair/convengine/intent/AgentIntentResolver.java`
- Prompt context `extra` now uses `session.promptTemplateVars()`.
- Clarification robustness improvement:
- If model signals clarification/collision but `clarificationQuestion` is empty and `followups` exists, first follow-up is used as clarification question.
- Prevents false rejection when `needsClarification` is true but question field is missing.

### 7) Reset Steps (New Pipeline Steps)
1. `src/main/java/com/github/salilvnair/convengine/engine/steps/ResetConversationStep.java` (new)
- Placement:
- `@MustRunAfter(LoadOrCreateConversationStep.class)`
- `@MustRunBefore(PersistConversationBootstrapStep.class)`
- Trigger conditions:
- `inputParams.reset`
- `inputParams.restart`
- `inputParams.conversation_reset`
- user message command: `reset`, `restart`, `/reset`, `/restart`
- Calls `session.resetForConversationRestart()`.
- Resets persisted conversation row fields (`intentCode`, `stateCode`, `contextJson`, `inputParamsJson`, `lastAssistantJson`, status/timestamp).
- Emits `CONVERSATION_RESET` audit with reason and post-reset snapshot.

2. `src/main/java/com/github/salilvnair/convengine/engine/steps/ResetResolvedIntentStep.java` (new)
- Placement:
- `@RequiresConversationPersisted`
- `@MustRunAfter(IntentResolutionStep.class)`
- `@MustRunBefore(FallbackIntentStateStep.class)`
- Configurable reset-intent detection:
- Reads `ce_config` key `RESET_INTENT_CODES`
- Default: `RESET_SESSION`
- Supports comma-separated values, normalized uppercase.
- If resolved intent matches configured reset code:
- Calls `session.resetForConversationRestart()`.
- Resets persisted conversation row similarly.
- Emits `CONVERSATION_RESET` audit with `matchedResetIntentCodes`.

### 8) Output, Planner, And History Consistency
1. `src/main/java/com/github/salilvnair/convengine/engine/mcp/McpPlanner.java`
- `PromptTemplateContext.extra` switched from `session.getInputParams()` to `session.promptTemplateVars()`.

2. `src/main/java/com/github/salilvnair/convengine/engine/response/format/provider/JsonOutputFormatResolver.java`
- Same switch to controlled prompt vars (`promptTemplateVars()`).

3. `src/main/java/com/github/salilvnair/convengine/engine/response/format/provider/TextOutputFormatResolver.java`
- Same switch to controlled prompt vars (`promptTemplateVars()`).

4. `src/main/java/com/github/salilvnair/convengine/engine/history/provider/AuditConversationHistoryProvider.java`
- Enhanced payload extraction for `data` wrapper:
- reads `data.text`, `data.output`, `data.question`.
- Improves history reconstruction from stream-style payload shapes.

### 9) Streaming Transport Support
1. `src/main/java/com/github/salilvnair/convengine/config/ConvEngineTransportConfig.java` (new)
- Binds `convengine.transport.*`.
- `Sse` config:
- `enabled` (default `true`)
- `emitterTimeoutMs` (default `1800000`)
- `Stomp` config:
- `enabled` (default `false`)
- `endpoint`, `appDestinationPrefix`, `topicPrefix`, `auditDestinationBase`, `allowedOriginPattern`, `sockJs`

2. `src/main/java/com/github/salilvnair/convengine/transport/sse/ConversationSseController.java` (new)
- Endpoint: `GET /api/v1/conversation/stream/{conversationId}`
- Produces `text/event-stream`.

3. `src/main/java/com/github/salilvnair/convengine/transport/sse/AuditSseService.java` (new)
- Implements `AuditEventListener`.
- Maintains emitters per conversation.
- Sends `CONNECTED` event on subscribe.
- On each audit, emits SSE event named by stage with `AuditStreamEventResponse` payload.

4. `src/main/java/com/github/salilvnair/convengine/transport/stomp/ConvEngineStompConfig.java` (new)
- Enabled only when `convengine.transport.stomp.enabled=true`.
- Registers STOMP endpoint (SockJS optional).
- Configures broker prefixes from transport config.

5. `src/main/java/com/github/salilvnair/convengine/transport/stomp/AuditStompPublisher.java` (new)
- Implements `AuditEventListener`.
- Publishes audit events via `SimpMessagingTemplate` to:
- `{auditDestinationBase}/{conversationId}`

### 10) Experimental SQL Generation
1. `src/main/java/com/github/salilvnair/convengine/config/ConvEngineExperimentalConfig.java` (new)
- Binds `convengine.experimental.enabled` (default `false`).

2. `src/main/java/com/github/salilvnair/convengine/experimental/ExperimentalSqlGenerationService.java` (new)
- Method: `generate(ExperimentalSqlGenerationRequest request)`
- Uses `LlmClient.generateText(...)` to generate SQL for `ce_*` tables.
- Builds strict system prompt with allowed enums and constraints.
- Safety checks:
- Reject-warning on forbidden verbs (`UPDATE`, `DELETE`, `DROP`, `ALTER`, `TRUNCATE`, `CREATE TABLE`).
- Warns if no `INSERT INTO ce_` found.
- Normalizes fenced markdown SQL output to raw SQL.
- Returns response with `success`, `warnings`, and advisory note.

## New/Updated Audit Stages In 1.0.8
1. `STEP_ENTER`
2. `STEP_EXIT`
3. `STEP_ERROR`
4. `STEP_HOOK_ERROR`
5. `CONVERSATION_RESET`

Each audit payload now gets normalized `_meta` with:
1. `stage`
2. `conversationId`
3. `emittedAt`
4. `intent` (resolved runtime/session-first)
5. `state` (resolved runtime/session-first)

## Configuration Keys Introduced/Used
1. `convengine.transport.sse.enabled`
2. `convengine.transport.sse.emitter-timeout-ms`
3. `convengine.transport.stomp.enabled`
4. `convengine.transport.stomp.endpoint`
5. `convengine.transport.stomp.app-destination-prefix`
6. `convengine.transport.stomp.topic-prefix`
7. `convengine.transport.stomp.audit-destination-base`
8. `convengine.transport.stomp.allowed-origin-pattern`
9. `convengine.transport.stomp.sock-js`
10. `convengine.experimental.enabled`
11. `ce_config.RESET_INTENT_CODES` (comma-separated, default `RESET_SESSION`)

## API Additions In 1.0.8
1. `GET /api/v1/conversation/audit/{conversationId}/trace`
2. `GET /api/v1/conversation/stream/{conversationId}` (SSE)
3. `POST /api/v1/conversation/experimental/generate-sql` (feature-flagged)
4. `POST /api/v1/conversation/message` now accepts `reset` in request body.

## Behavioral Notes For Consumers
1. Intent can now remain locked during incomplete schema collection, so intent/classifier may be intentionally skipped until schema completion.
2. Unknown system-injected prompt vars are tracked and filtered from prompt context exposure.
3. Reset can be triggered by input flags, command text, or configured intent code match.
4. Step hook exceptions are isolated and audited; they do not crash engine step execution.
5. Audit trace endpoint reconstructs step execution tree from audit events and can be consumed by timeline/trace UIs.

## Suggested Release Message (Short)
`1.0.8` adds step lifecycle hooks and audits, reset semantics (`RESET_SESSION` intent and input command resets), trace API, SSE/STOMP streaming audits, controlled prompt var exposure, and an experimental SQL-generation endpoint.
