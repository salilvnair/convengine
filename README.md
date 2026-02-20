# ConvEngine Java

ConvEngine is a deterministic, database-driven conversational workflow engine.

It is designed for auditable state machines, not free-form assistant behavior. Runtime behavior is declared in `ce_*` configuration tables and executed by an ordered step pipeline.

## Version

- Current library version: `1.0.15`

## Core Capabilities

- Deterministic intent + state progression
- Schema-driven data collection and slot completion
- Rule engine with ordered priorities and execution phases
- Configurable response resolution (`EXACT` and `DERIVED`)
- MCP tool planning + execution loop
- Full audit timeline and trace API
- SSE and STOMP streaming support

## API Endpoints

- `POST /api/v1/conversation/message`
- `GET /api/v1/conversation/audit/{conversationId}`
- `GET /api/v1/conversation/audit/{conversationId}/trace`
- `GET /api/v1/conversation/stream/{conversationId}`
- `POST /api/v1/conversation/experimental/generate-sql` (feature-flagged)

## Runtime Step Pipeline

Step order is DAG-resolved from annotations (`@MustRunAfter`, `@MustRunBefore`, `@RequiresConversationPersisted`) and validated at startup.

Main runtime stages:

1. Conversation bootstrap/load/reset
2. User input audit + policy checks
3. Dialogue-act classification
4. Interaction policy decision
5. Action lifecycle/disambiguation/guardrail
6. Intent resolution + fallback/reset handling
7. Container data and MCP/tool orchestration
8. Schema extraction + auto-advance facts
9. Rules execution (multi-pass)
10. State graph validation (validate mode)
11. Response resolution
12. Memory update
13. Persist + pipeline end guard

## Data Model

### Control-plane (configuration) tables

- `ce_config`
- `ce_container_config`
- `ce_intent`
- `ce_intent_classifier`
- `ce_output_schema`
- `ce_prompt_template`
- `ce_response`
- `ce_rule`
- `ce_policy`
- `ce_mcp_tool`
- `ce_mcp_db_tool`
- `ce_pending_action`

### Runtime/transactional tables

- `ce_conversation`
- `ce_audit`
- `ce_conversation_history`
- `ce_llm_call_log`
- `ce_validation_snapshot`

## Enum Contracts

### `ce_response`

- `response_type`: `EXACT`, `DERIVED`
- `output_format`: `TEXT`, `JSON`

### `ce_prompt_template`

- `response_type`: `TEXT`, `JSON`, `SCHEMA_JSON`

### `ce_rule`

- `rule_type`: `EXACT`, `REGEX`, `JSON_PATH`
- `phase`: `PIPELINE_RULES`, `AGENT_POST_INTENT`, `MCP_POST_LLM`, `TOOL_POST_EXECUTION`
- `action`: `SET_INTENT`, `SET_STATE`, `SET_JSON`, `GET_CONTEXT`, `GET_SCHEMA_JSON`, `GET_SESSION`, `SET_TASK`
- `state_code`: `NULL`, `ANY`, or exact state

### `ce_intent_classifier`

- `rule_type`: `REGEX`, `CONTAINS`, `STARTS_WITH`

## Flow Configuration (application.yml)

Flow behavior is file-configured from consumer app config.

```yaml
convengine:
  flow:
    dialogue-act:
      resolute: REGEX_THEN_LLM # REGEX_ONLY | REGEX_THEN_LLM | LLM_ONLY
      llm-threshold: 0.90
    interaction-policy:
      execute-pending-on-affirm: true
      reject-pending-on-negate: true
      fill-pending-slot-on-non-new-request: true
      require-resolved-intent-and-state: true
      matrix:
        "PENDING_ACTION:AFFIRM": EXECUTE_PENDING_ACTION
        "PENDING_ACTION:NEGATE": REJECT_PENDING_ACTION
        "PENDING_SLOT:QUESTION": FILL_PENDING_SLOT
    action-lifecycle:
      enabled: true
      ttl-turns: 3
      ttl-minutes: 30
    disambiguation:
      enabled: true
      max-options: 5
    guardrail:
      enabled: true
      sanitize-input: true
      require-approval-for-sensitive-actions: false
      approval-gate-fail-closed: false
      sensitive-patterns: []
    state-graph:
      enabled: true
      soft-block-on-violation: false
      allowed-transitions: {}
    tool-orchestration:
      enabled: true
    memory:
      enabled: true
      summary-max-chars: 1200
      recent-turns-for-summary: 3
```

Consumer contract details:

- `docs/consumer-contract-v2.md`

## Streaming Configuration

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
```

## Audit Configuration

```yaml
convengine:
  audit:
    enabled: true
    persist-meta: true
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
```

## Consumer Setup

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

Required consumer bean:

```java
public interface LlmClient {
  String generateText(String hint, String contextJson);
  String generateJson(String hint, String jsonSchema, String contextJson);
  float[] generateEmbedding(String input);
}
```

## Extension Points

- `EngineStepHook`
- `RuleActionResolver` (custom rule actions)
- `CeRuleTask`/`CeTask` task beans for `SET_TASK`
- `ResponseTransformer` / container interceptors
- MCP tool executors/adapters by tool group

## Reset Semantics

Reset can be triggered by:

- request `reset=true`
- input params `reset=true`, `restart=true`, `conversation_reset=true`
- user text commands (`reset`, `restart`, `/reset`, `/restart`)
- configured reset intents (`ResetResolvedIntentStep.RESET_INTENT_CODES`)

## SQL Generation

See:

- `src/main/resources/prompts/SQL_GENERATION_AGENT.md`

This is the authoritative guide for generating `INSERT`-only control-plane seed SQL aligned to current DDL and runtime contracts.

## Engineering Notes

- Prefer behavior changes in tables/config before code changes.
- Keep rule transitions explicit and auditable.
- Keep prompts schema-aware and constrained.
- Validate with audit trace before rollout.
