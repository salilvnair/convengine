# ConvEngine Java

ConvEngine is a deterministic, database-configured conversational workflow engine for enterprise-grade, auditable conversation systems.

It is designed for stateful workflows where intent, schema collection, rules, and responses must be explicit and traceable.

## Core Architecture

### API Layer
- `POST /api/v1/conversation/message`
- `GET /api/v1/conversation/audit/{conversationId}`
- `GET /api/v1/conversation/audit/{conversationId}/trace`

### Engine Layer
- `DefaultConversationalEngine` creates session + executes pipeline
- `EnginePipelineFactory` builds annotation-driven DAG order
- `EngineStep` units operate on mutable `EngineSession`

### Persistence Layer
- `ce_*` tables are the control plane and runtime state
- `ce_audit` is the source of truth for postmortems

### LLM Layer
- Consumer provides `LlmClient` implementation
- Engine uses constrained prompts and JSON contracts

## Pipeline (Typical)

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

Exact order is resolved via `@MustRunAfter`, `@MustRunBefore`, `@RequiresConversationPersisted`, and terminal/bootstrap annotations.

## Control Plane Tables

- `ce_intent`
- `ce_intent_classifier`
- `ce_output_schema`
- `ce_prompt_template`
- `ce_response`
- `ce_rule`
- `ce_policy`
- `ce_mcp_tool`
- `ce_mcp_db_tool`
- `ce_config`
- `ce_conversation`
- `ce_audit`

## Implemented Value Matrix (Source-of-Truth)

### Response
- `ce_response.response_type`: `EXACT`, `DERIVED`
- `ce_response.output_format`: `TEXT`, `JSON`

### Rule Types
- Implemented resolvers: `EXACT`, `REGEX`, `JSON_PATH`

### Rule Actions
- `SET_INTENT`
- `SET_STATE`
- `SET_JSON`
- `GET_CONTEXT`
- `GET_SCHEMA_JSON`
- `GET_SESSION`
- `SET_TASK`

Migration note:
- If existing `ce_rule.action` rows use `GET_SCHEMA_EXTRACTED_DATA`, update them to `GET_SCHEMA_JSON`.

### OutputType (engine templates)
- `TEXT`
- `JSON`
- `SCHEMA_JSON`

## Session Continuity and Intent Lock

During schema collection, intent can be lock-pinned to prevent resolver drift on subsequent turns.

- lock is persisted in `context_json.intent_lock`
- unlocked when schema completes or schema path is absent

## Reset Semantics

Conversation reset can be triggered by:

- request body: `reset=true`
- input params: `reset=true | restart=true | conversation_reset=true`
- message commands: `reset`, `restart`, `/reset`, `/restart`
- resolved intent codes configured via `ce_config`

Config:

```sql
INSERT INTO ce_config (config_type, config_key, config_value, enabled)
VALUES ('ResetResolvedIntentStep', 'RESET_INTENT_CODES', 'RESET_SESSION,START_OVER', true);
```

Default reset intent code: `RESET_SESSION`.

## Consumer Extension Points

### 1) Step interception (`EngineStepHook`)

```java
@Component
public class MyHook implements EngineStepHook {
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

### 2) Response transformer
- Annotate bean with `@ResponseTransformer(intent, state)`
- Implement `ResponseTransformerHandler`

### 3) Container extensions
- `@ContainerDataTransformer(intent, state)`
- request/response interceptors for container execution

### 4) Rule task execution
- `SET_TASK` action delegates to custom task executor paths

## Streaming Transport (Configurable)

### SSE (default enabled)
- Endpoint: `GET /api/v1/conversation/stream/{conversationId}`
- Streams audit events as they are persisted

### STOMP/WebSocket (default disabled)
- Enable with `convengine.transport.stomp.enabled=true`
- Endpoint: `convengine.transport.stomp.endpoint` (default `/ws-convengine`)
- Topic: `convengine.transport.stomp.auditDestinationBase/{conversationId}`
  (default `/topic/convengine/audit/{conversationId}`)
- Broker mode defaults to in-memory simple broker; optional relay mode is configurable.

### Transport properties

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

### Audit dispatch, backpressure, and stage controls

By default, audit save + publish behaves as before (sync dispatch, all stages, no rate-limit).

```yaml
convengine:
  audit:
    enabled: true
    level: ALL # ALL | STANDARD | ERROR_ONLY | NONE
    include-stages: [] # supports wildcard, e.g. STEP_* or INTENT_*
    exclude-stages: [] # supports wildcard
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

You can also enable key features using annotations on consumer app:

```java
@EnableConvEngine
@EnableConvEngineAsyncAuditDispatch
@EnableConvEngineStompBrokerRelay
@SpringBootApplication
public class MyApplication {
}
```

### Full `application.yml` example (SSE + STOMP + Experimental)

```yaml
server:
  port: 8080

convengine:
  transport:
    sse:
      enabled: true
      emitter-timeout-ms: 1800000
    stomp:
      enabled: true
      endpoint: /ws-convengine
      app-destination-prefix: /app
      topic-prefix: /topic
      audit-destination-base: /topic/convengine/audit
      allowed-origin-pattern: "*"
      sock-js: true
  experimental:
    enabled: true
```

### How SSE/STOMP flow reaches engine

1. Client calls `POST /api/v1/conversation/message`.
2. `ConversationController` invokes `ConversationalEngine`.
3. Pipeline steps run (`IntentResolutionStep`, `SchemaExtractionStep`, `RulesStep`, `ResponseResolutionStep`, etc.).
4. Each important stage is written to `ce_audit`.
5. `AuditEventPublisher` publishes that stage to enabled transports:
   - SSE: `GET /api/v1/conversation/stream/{conversationId}`
   - STOMP topic: `/topic/convengine/audit/{conversationId}`
6. UI listens and updates timeline/state in near real-time.

### Consumer usage pattern

1. Send message through REST (`/message`) for deterministic request/response.
2. Subscribe to SSE (or STOMP) for streaming audit telemetry.
3. On each stream event, re-fetch:
   - `GET /audit/{conversationId}` (raw timeline), or
   - `GET /audit/{conversationId}/trace` (normalized step/stack view).
4. Render intent/state/response from `/message`; render diagnostics from stream + audit API.

### React Vite client example (`convengine.api.js`)

```js
const API_BASE = "http://localhost:8080/api/v1/conversation";

export async function sendMessage(conversationId, message, inputParams = {}, reset = false) {
  const res = await fetch(`${API_BASE}/message`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ conversationId, message, inputParams, reset })
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function fetchAudits(conversationId) {
  const res = await fetch(`${API_BASE}/audit/${conversationId}`);
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export function subscribeConversationSse(conversationId, handlers = {}) {
  const source = new EventSource(`${API_BASE}/stream/${conversationId}`);

  source.onopen = () => handlers.onConnected?.();
  source.onerror = (error) => handlers.onError?.(error);

  ["CONNECTED", "USER_INPUT", "STEP_ENTER", "STEP_EXIT", "STEP_ERROR", "ASSISTANT_OUTPUT", "ENGINE_RETURN"]
    .forEach((stage) => {
      source.addEventListener(stage, (event) => {
        let data = null;
        try { data = event.data ? JSON.parse(event.data) : null; } catch {}
        handlers.onEvent?.({ stage, data, raw: event });
      });
    });

  return {
    close() {
      source.close();
      handlers.onClosed?.();
    }
  };
}

// STOMP scaffold (optional)
// npm install @stomp/stompjs sockjs-client
// import { Client } from "@stomp/stompjs";
// import SockJS from "sockjs-client";
//
// export function subscribeConversationStomp(conversationId, handlers = {}) {
//   const client = new Client({
//     webSocketFactory: () => new SockJS("http://localhost:8080/ws-convengine"),
//     reconnectDelay: 5000,
//     onConnect: () => {
//       handlers.onConnected?.();
//       client.subscribe(`/topic/convengine/audit/${conversationId}`, (msg) => {
//         let data = msg.body;
//         try { data = JSON.parse(msg.body); } catch {}
//         handlers.onEvent?.(data);
//       });
//     },
//     onStompError: (frame) => handlers.onError?.(frame)
//   });
//
//   client.activate();
//   return {
//     close() {
//       client.deactivate();
//       handlers.onClosed?.();
//     }
//   };
// }
```

### React Vite typed client example (`convengine.api.ts`)

```ts
const API_BASE = "http://localhost:8080/api/v1/conversation";

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
  - source class/file metadata

## Experimental SQL Config Generation API

Feature flag:

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
  "scenario": "Build workflow for outage ticket intake",
  "domain": "network support",
  "constraints": "Require account id before status lookup",
  "includeMcp": true
}
```

Response:
- `success`
- `sql`
- `warnings`
- `note`

The generator uses `LlmClient.generateText(...)` and applies safety checks (forbidden DDL/DML detection).

DDL note:
- In `src/main/resources/sql/ddl.sql`, the `ce_config` seed for `config_id=9` currently has a duplicated column-list line before `VALUES`.
- Correct form is a single line:
  - `INSERT INTO ce_config (config_id, config_type, config_key, config_value, enabled, created_at)`

## Auto-configuration

Enable in consumer app:

```java
@EnableConvEngine(stream = true)
@SpringBootApplication
public class App {
}
```

Also provide an `LlmClient` bean.

`stream` behavior:
- `stream = true` (default): framework expects at least one stream transport enabled.
  Startup fails if both are disabled:
  - `convengine.transport.sse.enabled=false`
  - `convengine.transport.stomp.enabled=false`
- `stream = false`: framework ignores SSE/STOMP beans even if transport flags are true.
  Use only plain REST controller flow (`/message`, `/audit`, `/audit/{id}/trace`).

## Design Constraints

- Keep workflow behavior in DB config first
- Avoid hidden transitions in code
- Keep LLM outputs constrained and auditable
- Prefer deterministic rule/response mapping over ad-hoc branching
