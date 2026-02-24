# ConvEngine Java

ConvEngine is a deterministic, database-driven conversational workflow engine.

It is designed for auditable state machines, not free-form assistant behavior. Runtime behavior is declared in `ce_*` configuration tables and executed by an ordered step pipeline.

## Version

- Current library version: `2.0.3`

## Detailed Architecture Upgrades (v2.0.3)

### 6. Caching, Async Persistence & Deep Audit
- **Spring `@Cacheable` + `@Async` Control Plane**: Persistences operations are now fire-and-forget background threads to erase RDBMS I/O latency, cleanly fronted by real-time synchronized cache managers. Enabled seamlessly via `@EnableConvEngineCaching` & `@EnableConvEngineAsyncConversation`.
- **`CacheInspectAuditStep`**: Dedicated `convengine.audit.cache-inspector` debug functionality to serialize the live hydrated cache properties to `ce_audit` as `CACHE_INSPECTION` payloads. 
- **Global `_cache` Logging**: `DbAuditService` captures memory footprints passively alongside all discrete stage checkpoints when inspection triggers are enabled.
- **Executor Macro History Control**: Reduced database redundant scans for conversational memory blocks by extracting provisioning arrays `historyProvider.lastTurns` into `DefaultConversationalEngine.process()`.

### 1. Robust Pending Action & Task Execution Lifecycle
- **Introduction of `ce_pending_action`**: A new core configuration table used to catalog multi-turn action candidates by intent, state, and `action_key`. Features indexed lookups `idx_ce_pending_action_lookup` across `enabled`, `action_key`, `intent_code`, and `state_code`.
- **`PendingActionRepository`**: Added Spring Data JPA queries focused on filtering eligible actions by phase, intent (`intentCode IS NULL OR ...`), state (`stateCode IS NULL OR ...`), and enabled status, ordering strictly by integer `priority`.
- **`CePendingAction` Entity**: A JPA entity mapping to `ce_pending_action` with `bean_name`, `method_names`, `priority`, `intent_code`, and `state_code` enabling highly parameterized late-binding invocations.
- **`ActionLifecycleStep`**: A top-level orchestration step responsible for managing the state transitions of modular pending actions within the conversation. Actions are stored in the session context as `IN_PROGRESS` pending definitive confirmation from the user. It evaluates configurable Time-To-Live parameters (`convengine.flow.action-lifecycle.ttl-turns` and `ttl-minutes`).
- **`PendingActionStep`**: A core execution step designed to finalize these actions. It routes the action to the corresponding `bean_name` and `method_names` if the user successfully confirms the action sequence.
- **`CeTaskExecutor`**: A specialized executor pattern designed to securely evaluate and invoke pending tasks synchronously within the strict temporal limits of the conversational turn.
- **Context Lifecycle Persistence**: The engine tracks the holistic lifecycle of these actions (`OPEN`, `IN_PROGRESS`, `EXECUTED`, `REJECTED`, `EXPIRED`) purely in the stringified JSON memory context map (`pending_action_runtime`), meticulously decoupled from the static table definition.
- **Disambiguation Matrix Controls**: Pending actions that are ambiguous or require explicit missing slot inputs before execution dynamically trigger the new disambiguation mechanisms to halt execution, stash the context, and query the user.

### 2. Deterministic Interaction Policies & Dialogue Acts
- **`DialogueActStep`**: A brand new classification pipeline step heavily augmenting the traditional NLP pass. Instead of relying purely on heavy LLM intent classification for simple operational conversational shifts, this step evaluates the user's raw input against fast strict Pattern Regex patterns (`AFFIRM`, `NEGATE`, `EDIT`, `RESET`) before triggering fallback heuristic LLM logic.
- **Dialogue Act Taxonomy**: Standardized ENUM classification integrated precisely into `ConvEngineInputParamKey.DIALOGUE_ACT` including `AFFIRM` (yes, proceed), `NEGATE` (no, cancel), `EDIT` (change something), `RESET` (start over), `QUESTION` (inquiry), and `NEW_REQUEST` (topic switch).
- **`DialogueActResolveMode` Enum**: Explicit execution evaluation modes (`REGEX_ONLY`, `REGEX_THEN_LLM`, `LLM_ONLY`) allowing the system to rapidly identify acts using extremely fast regex thresholds prior to paying LLM generation costs.
- **`InteractionPolicyStep` & `InteractionPolicyDecision`**: The primary "brain" of the v2 matrix routing implementation. This step evaluates the `DialogueAct` alongside the current discrete `SessionState`. For instance, if a pending action is marked `IN_PROGRESS` in memory, and the new turn evaluates to the act `AFFIRM`, it makes an instantaneous deterministic policy decision `EXECUTE_PENDING_ACTION`. If it evaluates to `NEGATE`, it selects `REJECT_PENDING_ACTION`. If it's `QUESTION`, it evaluates to `FILL_PENDING_SLOT`. This massive enhancement completely bypasses stochastic LLM generative behaviors for discrete boolean pathing.

### 3. Strict MCP Tool Management and Orchestration Scoping
- **`CeMcpTool` Scope Isolation**: The `intent_code` and `state_code` columns were physically integrated into the `ce_mcp_tool` table architecture to formally mandate extreme compartmentalization of tool access limits. Tools are strictly bound to domains and are no longer assumed "global by default."
- **`McpToolRepository` Refactoring**: Updated JPQL definitions in the repository (`findEnabledByIntentAndState`, `findByToolCodeEnabledAndIntentAndState`) leveraging the Spring Data `@Param` annotation to guarantee tools are flawlessly filtered. The internal `McpPlanner` is logically isolated from discovering tools sitting externally to the current active conversational domain intent scope.
- **`McpToolRegistry` Improvements**: A hardened centralized registry mechanism uniquely responsible for standardizing `tool_group` domains and resolving perfectly legal tools for a given conversation cycle loop, mathematically preventing hallucinations where an LLM invokes an unbound tool.
- **`McpToolExecutor` Interface Expansion**: Designed a formalized executor functional contract natively implemented by a vast array of specialized business adapters for standardized output structures.
- **New Executor Adapter Implementations**: Delivered fully mocked execution wrappers capable of expanding to live implementations:
  - `McpDbToolExecutor`: For parsing database querying and modification parameter mapping tools.
  - `McpHttpApiToolExecutor`: For serializing outbound REST/SOAP API tool integrations.
  - `McpWorkflowActionToolExecutor`: For complex cross-system async workflow pipeline triggers.
  - `McpDocumentRetrievalToolExecutor`: For executing dynamic RAG (Retrieval-Augmented Generation) Knowledge Base queries.
  - `McpCalculatorTransformToolExecutor`: For executing deterministic programmatic mathematical transformations.
  - `McpFileToolExecutor`: For OS-level file parsing, reading, generation, and file management tools.
  - `McpNotificationToolExecutor`: For serializing logic aimed at SMS/Email/Push notification gateways.
- **Early-Exit Execution Guardrails**: `ToolOrchestrationStep` and `McpToolStep` logic matrices were fortified to immediately halt and bypass tool discovery planning on simple conversational greetings (e.g., regex checks against `hi`, `hello`, `good morning`) or explicit internal dialogue acts (`AFFIRM`, `NEGATE`) to drastically curb overhead computational latency and expensive LLM generative token expenditures.

### 4. Advanced Pipeline Governance: Memory, Guardrails, and Graphs
- **`GuardrailStep` Framework**: A foundational preventative pipeline step strategically positioned to screen dynamic inputs against hardcoded blocked configuration patterns or sanitize output payload injections before advancing. Generates robust metrics to soft-block or strictly hard-block the `ConvEngineInputParamKey.STATE_GRAPH_VALID` control parameter.
- **`DisambiguationStep` Integration**: An autonomous discrete evaluation system boundary. If multiple valid intents or competing multi-turn pending actions exhibit mathematically adjacent probabilities (Confidence score collisions), this step halts the internal execution state transitions and forces a deterministic query back to the end user for explicit disambiguation.
- **`StateGraphStep` Validation**: Built an internal compliance enforcement algorithm explicitly assessing whether the current proposed transition route from `State A -> State B` functionally complies with the statically mapped topologies inside the `convengine.flow.state-graph.allowed-transitions` logic limits.
- **`MemoryStep` and `ConversationMemoryStore` Context Modeling**: Engineered a long-term conversation compression service layer. The orchestration engine dynamically aggregates rolling conversation turns bounded by the `recent-turns-for-summary` threshold and compresses them into a highly compact `ConvEngineInputParamKey.MEMORY_SESSION_SUMMARY`. This drastically scales up context capacities, permitting the underlying LLM to seamlessly address sliding-window follow-up questions without wasting tokens continuously re-parsing huge historical payload stacks.

### 5. Deterministic Evaluation and Replay CI/CD Logics
- **`ConversationReplayService` Orchestration**: Wrote advanced system test capabilities modeled exclusively for deep auditing metrics and automated CI/CD gating strategies. It fundamentally enables developers to parse a target `conversation_id` string into the service to perfectly replay historical conversational turns sequentially across the precise state parameters to test robustness over time.
- **`TraceExpectation` Object Structure**: Represents the mathematical and contextual logical assertions required during a turn replay scenario (e.g., `Assert Intent == X`, `Assert Dialogue Act == Y`).
- **`TraceReplayResult` & `TraceTurnResult` Analytics**: Deployed overarching replay evaluation metrics classes designed to empower automated pipelines with the tooling necessary to validate whether newly submitted behavior rules applied directly within `ce_rule` or `ce_policy` unexpectedly fractured long-standing static historically recorded conversation progression structures.
- **SQL Initialization DDL Enhancements**: Segmented and significantly refactored database procedural seeding generation structures intended for clean, error-free automated platform tests. Explicitly divided the sprawling monolithic `ddl.sql` logic into dedicated dialect-specific SQL instances (`seed_sqlite.sql`, `seed_postgres.sql`, `seed_oracle.sql`). Spliced all auto-generated DBeaver boilerplate syntax elements and strategically configured dependency-oriented `DROP TABLE` definitions to ensure identically idempotent and stateless automated unit test deployment loops continuously without breaking consistency schemas.

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
2. Cache inspection audit (if enabled)
3. User input audit + policy checks
4. Dialogue-act classification
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
