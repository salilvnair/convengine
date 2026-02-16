# ConvEngine Java

ConvEngine is a deterministic, database-driven conversational workflow engine for enterprise use-cases.

It is designed for auditable, stateful flows where intent resolution, schema extraction, rule transitions, and response generation must be explicit and traceable.

## Version

- Current library version: `1.0.8`

## What Changed In 1.0.8 (Revamp Summary)

### Engine and pipeline
- Added full step lifecycle audit stages: `STEP_ENTER`, `STEP_EXIT`, `STEP_ERROR`.
- Added `EngineStepHook` extension point for before/after/error step intervention.
- Added typed step matching using `EngineStep.Name` enum (string fallback retained for compatibility).
- Added `ResetConversationStep` (input/message driven reset).
- Added `ResetResolvedIntentStep` (intent-driven reset via `ce_config`).
- Added `PipelineEndGuardStep` protection and broader pipeline consistency checks.

### Session and intent continuity
- `EngineSession` hardened for controlled prompt variable exposure (`promptTemplateVars()`).
- Added intent-lock lifecycle so incomplete schema collection does not repeatedly re-resolve intent.
- Added full restart/reset state cleanup via `resetForConversationRestart()`.
- Added persisted conversation bootstrap safety in `EngineSessionFactory`.

### Audit, trace, streaming
- Added normalized trace API: `GET /api/v1/conversation/audit/{conversationId}/trace`.
- Added stage filtering/verbosity/rate limiting (`convengine.audit.*`).
- Added configurable audit dispatch modes with backpressure controls.
- Added configurable persistence mode for audit: `IMMEDIATE` or `DEFERRED_BULK`.
- Added SSE transport and STOMP/WebSocket transport as pluggable audit streams.
- Added optional STOMP broker relay mode (`SIMPLE` or `RELAY`).

### Experimental capabilities
- Added experimental SQL generation endpoint:
  - `POST /api/v1/conversation/experimental/generate-sql`
- Added SQL generation guide in classpath prompt resource:
  - `src/main/resources/prompts/SQL_GENERATION_AGENT.md`
- SQL generator covers non-transactional `ce_*` tables and excludes runtime tables.

### Rule/action naming updates
- Rule action `GET_SCHEMA_EXTRACTED_DATA` migrated to `GET_SCHEMA_JSON`.

## Core API

### Endpoints
- `POST /api/v1/conversation/message`
- `GET /api/v1/conversation/audit/{conversationId}`
- `GET /api/v1/conversation/audit/{conversationId}/trace`
- `GET /api/v1/conversation/stream/{conversationId}` (SSE)
- `POST /api/v1/conversation/experimental/generate-sql` (feature-flagged)

## Runtime Step Set (Canonical)

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

Order is enforced by step annotations (`@MustRunAfter`, `@MustRunBefore`, `@RequiresConversationPersisted`) and pipeline DAG validation.

## Control Plane Data Model

### Non-transactional configuration tables
- `ce_config`
- `ce_container_config`
- `ce_intent`
- `ce_intent_classifier`
- `ce_mcp_tool`
- `ce_output_schema`
- `ce_policy`
- `ce_prompt_template`
- `ce_response`
- `ce_rule`
- `ce_mcp_db_tool`

### Runtime/transactional tables
- `ce_conversation`
- `ce_audit`
- `ce_llm_call_log`
- `ce_validation_snapshot`

## Enum / Value Matrix (Current)

### `ce_response`
- `response_type`: `EXACT`, `DERIVED`
- `output_format`: `TEXT`, `JSON`

### `ce_prompt_template`
- `response_type`: `TEXT`, `JSON`, `SCHEMA_JSON`

### `ce_rule`
- `rule_type`: `EXACT`, `REGEX`, `JSON_PATH`
- `action`: `SET_INTENT`, `SET_STATE`, `SET_JSON`, `GET_CONTEXT`, `GET_SCHEMA_JSON`, `GET_SESSION`, `SET_TASK`

### `ce_intent_classifier`
- `rule_type`: `REGEX`, `CONTAINS`, `STARTS_WITH`

## Reset Semantics

Conversation reset can be triggered by:
- request body: `reset=true`
- input params: `reset=true` or `restart=true` or `conversation_reset=true`
- message commands: `reset`, `restart`, `/reset`, `/restart`
- resolved intent code matched by config key `RESET_INTENT_CODES`

### Intent-driven reset config

```sql
INSERT INTO ce_config (config_id, config_type, config_key, config_value, enabled)
VALUES (100, 'ResetResolvedIntentStep', 'RESET_INTENT_CODES', 'RESET_SESSION,START_OVER', true);
```

Default reset intent code is `RESET_SESSION`.

## Consumer Bootstrapping

```java
import com.github.salilvnair.convengine.annotation.EnableConvEngine;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableConvEngine(stream = true)
@SpringBootApplication
public class MyApplication {
  public static void main(String[] args) {
    SpringApplication.run(MyApplication.class, args);
  }
}
```

### Optional feature annotations

```java
import com.github.salilvnair.convengine.annotation.EnableConvEngineAsyncAuditDispatch;
import com.github.salilvnair.convengine.annotation.EnableConvEngineStompBrokerRelay;

@EnableConvEngineAsyncAuditDispatch
@EnableConvEngineStompBrokerRelay
```

## Required Consumer Bean

Provide an `LlmClient` implementation from consumer app.

```java
public interface LlmClient {
  String generateText(String hint, String contextJson);
  String generateJson(String hint, String jsonSchema, String contextJson);
  float[] generateEmbedding(String input);
}
```

## Engine Extension Points

### 1) Step hooks (`EngineStepHook`)

```java
import com.github.salilvnair.convengine.engine.hook.EngineStepHook;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import org.springframework.stereotype.Component;

@Component
public class SchemaHintHook implements EngineStepHook {
  @Override
  public boolean supports(EngineStep.Name stepName, EngineSession session) {
    return EngineStep.Name.SchemaExtractionStep == stepName;
  }

  @Override
  public void beforeStep(EngineStep.Name stepName, EngineSession session) {
    session.putInputParam("consumer_hint", "compact");
  }
}
```

### 2) Response transformation
- `@ResponseTransformer` + `ResponseTransformerHandler`

### 3) Container transformation/interception
- `@ContainerDataTransformer` + `ContainerDataTransformerHandler`
- `@ContainerDataInterceptor`

## Streaming Transport

### `@EnableConvEngine(stream = true)` behavior
- Startup fails if both transports are disabled:
  - `convengine.transport.sse.enabled=false`
  - `convengine.transport.stomp.enabled=false`

### `@EnableConvEngine(stream = false)` behavior
- Streaming transport checks are skipped.
- Core REST flow remains active.

### Transport configuration

```yaml
convengine:
  transport:
    sse:
      enabled: true
      emitter-timeout-ms: 1800000
    stomp:
      enabled: false
      endpoint: /ws-convengine
      app-destination-prefix: /app
      topic-prefix: /topic
      audit-destination-base: /topic/convengine/audit
      allowed-origin-pattern: "*"
      sock-js: true
      broker:
        mode: SIMPLE # SIMPLE | RELAY
        relay-destination-prefixes: /topic,/queue
        relay-host: localhost
        relay-port: 61613
        client-login: ""
        client-passcode: ""
        system-login: ""
        system-passcode: ""
        virtual-host: ""
        system-heartbeat-send-interval-ms: 10000
        system-heartbeat-receive-interval-ms: 10000
```

## Audit Controls

```yaml
convengine:
  audit:
    enabled: true
    level: ALL # ALL | STANDARD | ERROR_ONLY | NONE
    include-stages: []
    exclude-stages: []
    persistence:
      mode: IMMEDIATE # IMMEDIATE | DEFERRED_BULK
      jdbc-batch-size: 200
      max-buffered-events: 5000
      flush-stages: ENGINE_KNOWN_FAILURE,ENGINE_UNKNOWN_FAILURE
      final-step-names: PipelineEndGuardStep
      flush-on-stop-outcome: true
    dispatch:
      async-enabled: false
      worker-threads: 2
      queue-capacity: 2000
      rejection-policy: CALLER_RUNS # CALLER_RUNS | DROP_NEWEST | DROP_OLDEST | ABORT
      keep-alive-seconds: 60
    rate-limit:
      enabled: false
      max-events: 200
      window-ms: 1000
      per-conversation: true
      per-stage: true
      max-tracked-buckets: 20000
```

## Audit Flow (REST + Stream)

1. Client calls `POST /message`.
2. Engine executes pipeline.
3. Stages are persisted to `ce_audit`.
4. Audit listeners publish to enabled SSE/STOMP channels.
5. Client can consume:
   - `GET /audit/{conversationId}`
   - `GET /audit/{conversationId}/trace`
   - live SSE/STOMP events

## Frontend Integration (TypeScript SSE Helper)

Use this from a Vite/React client (or any TS frontend) to call REST `/message` and subscribe to stream events.

```ts
const API_BASE = "/api/v1/conversation";

export type SseStage =
  | "CONNECTED"
  | "USER_INPUT"
  | "STEP_ENTER"
  | "STEP_EXIT"
  | "STEP_ERROR"
  | "ASSISTANT_OUTPUT"
  | "ENGINE_RETURN";

export interface ConversationEvent<T = unknown> {
  stage: SseStage;
  data: T | null;
  raw: MessageEvent;
}

export interface StreamHandlers<T = unknown> {
  onConnected?: () => void;
  onEvent?: (event: ConversationEvent<T>) => void;
  onError?: (error: Event) => void;
  onClosed?: () => void;
}

export async function sendMessage(
  conversationId: string,
  message: string,
  inputParams: Record<string, unknown> = {},
  reset = false
) {
  const res = await fetch(`${API_BASE}/message`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ conversationId, message, inputParams, reset })
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export function subscribeConversationSse<T = unknown>(
  conversationId: string,
  handlers: StreamHandlers<T> = {}
) {
  const source = new EventSource(`${API_BASE}/stream/${conversationId}`);
  const stages: SseStage[] = [
    "CONNECTED",
    "USER_INPUT",
    "STEP_ENTER",
    "STEP_EXIT",
    "STEP_ERROR",
    "ASSISTANT_OUTPUT",
    "ENGINE_RETURN"
  ];

  source.onopen = () => handlers.onConnected?.();
  source.onerror = (error) => handlers.onError?.(error);

  stages.forEach((stage) => {
    source.addEventListener(stage, (event) => {
      let data: T | null = null;
      try {
        data = (event as MessageEvent).data ? JSON.parse((event as MessageEvent).data) as T : null;
      }
      catch {
        data = null;
      }
      handlers.onEvent?.({ stage, data, raw: event as MessageEvent });
    });
  });

  return {
    close() {
      source.close();
      handlers.onClosed?.();
    }
  };
}
```

## Audit and Trace APIs

### Raw audit
- `GET /api/v1/conversation/audit/{conversationId}`

### Normalized trace
- `GET /api/v1/conversation/audit/{conversationId}/trace`
- Includes:
  - step timeline (`STEP_ENTER`, `STEP_EXIT`, `STEP_ERROR`)
  - stage stream in order
  - source class metadata

## Experimental SQL Generation

Enable flag:

```yaml
convengine:
  experimental:
    enabled: true
```

Endpoint:
- `POST /api/v1/conversation/experimental/generate-sql`

Request:

```json
{
  "scenario": "Disconnect electricity for account",
  "domain": "utilities",
  "constraints": "Collect accountNumber and disconnectDate first",
  "includeMcp": true
}
```

Response fields:
- `success`
- `sql`
- `warnings`
- `note`

Safety constraints include forbidden statement detection and required-table coverage checks.

## Notes

- SQL generation reference guide lives at:
  - `src/main/resources/prompts/SQL_GENERATION_AGENT.md`
- Latest DDL lives at:
  - `src/main/resources/sql/ddl.sql`

## Design Principles

- Keep behavior in DB config first.
- Keep runtime deterministic and auditable.
- Use LLM only in constrained, schema-bound paths.
- Prefer explicit `ce_rule` transitions over hidden branching logic.
