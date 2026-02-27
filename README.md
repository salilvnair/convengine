# ConvEngine Java

ConvEngine is a deterministic, database-driven conversational workflow engine.

It is designed for auditable state machines, not free-form assistant behavior. Runtime behavior is declared in `ce_*` configuration tables and executed by an ordered step pipeline.

## Version

- Current library version: `2.0.8`

### CE Verbose Telemetry, Stream Envelope, and MCP Guardrails (v2.0.8)
- **Database-driven verbose messaging (`ce_verbose`)**: Added `ce_verbose` as a first-class control table with entity/repository/cache support (`CeVerbose`, `VerboseRepository`, `StaticConfigurationCacheService#getAllVerboses`), startup preload, and scope validation (`intent_code`, `state_code`, `step_match`, `step_value`, `determinant`).
- **Dedicated SQL assets for verbose rollout**: Added standalone scripts:
  - `src/main/resources/sql/verbose_ddl.sql`
  - `src/main/resources/sql/verbose_seed.sql`
  and merged equivalent `ce_verbose` DDL + seed rows into main dialect files (`ddl*.sql`, `seed*.sql`).
- **Pipeline step telemetry model**: Added `StepInfo` and `EngineSession.stepInfos` capture (`recordStepEnter`, `recordStepExit`, `recordStepError`) so each turn now carries deterministic step timing/outcome metadata in `session`.
- **Verbose event resolver and dispatcher**: Added `VerboseMessagePublisher` + DB resolver (`DbVerboseMessageResolver`) to resolve human-readable progress/error text by priority and context (`intent`, `state`, `step`, `determinant`, `rule_id`, `tool_code`).
- **Verbose emission across core runtime**: Added deterministic publish points in:
  - `EnginePipelineFactory` (`STEP_ENTER`, `STEP_EXIT`, `STEP_ERROR`)
  - `AgentIntentResolver` (`AGENT_INTENT_*`)
  - `RulesStep` (`RULE_MATCH`, `RULE_APPLIED`)
  - `ToolOrchestrationStep` (`TOOL_ORCHESTRATION_*`)
  - `McpToolStep` (`MCP_TOOL_CALL`, `MCP_TOOL_RESULT`, `MCP_TOOL_ERROR`, `MCP_FINAL_ANSWER`)
  - resolver factories (`RULE_ACTION_RESOLVER_*`, `RESPONSE_TYPE_RESOLVER_*`, `OUTPUT_FORMAT_RESOLVER_*`)
- **SSE/STOMP payload contract upgrade**: `AuditStreamEventResponse` now includes `eventType` and optional `verbose` payload. Stream transports publish both `AUDIT` and `VERBOSE` envelopes on the same conversation channel.
- **MCP schema completeness guardrail**: Added `MCP_SKIPPED_SCHEMA_INCOMPLETE` stage and `STATUS_SKIPPED_SCHEMA_INCOMPLETE` status. `McpToolStep` now skips MCP execution when required schema fields are missing and writes lifecycle metadata deterministically.

### HTTP API MCP Revamp (v2.0.7)
- **Per-tool consumer handlers**: Added `HttpApiToolHandler` so consumers can implement one Spring bean per `tool_code` (for example `crm.lookup`, `order.status`) instead of building a large central switch block.
- **Backward compatible fallback**: Existing `HttpApiExecutorAdapter` support remains; it is used when no matching `HttpApiToolHandler` is found.
- **Normalized observation payloads**: `McpHttpApiToolExecutor` now normalizes handler outputs into JSON-safe strings for MCP observations and planner loops. Maps/POJOs serialize directly, JSON strings pass through, and plain text is wrapped as `{"text":"..."}`.
- **Advanced HTTP execution model**: Added `HttpApiRequestingToolHandler` + `HttpApiToolInvoker` for framework-managed timeout policies, retry/backoff, circuit breaker windows, auth injection (`API_KEY`, static `BEARER`), and response mapping (`RAW_JSON`, `JSON_PATH`, `FIELD_TEMPLATE`, `MAPPER_CLASS`, `TEXT`) while preserving existing handlers/adapters.
- **[api-processor](https://github.com/salilvnair/api-processor) native MCP path**: Added `HttpApiApiProcessorToolHandler` so consumers can execute MCP HTTP tools directly through `RestWebServiceFacade` (`prepareRequest -> delegate.invoke -> processResponse`) and return mapped responses to ConvEngine.
- **Intent/state-scoped planner prompts**: Added `ce_mcp_planner` table and runtime selection in `McpPlanner` so each use case can own planner prompts by `intent_code` + `state_code` instead of globally overriding `ce_config`.

### Advanced DB Knowledge MCP (v2.0.7)
- **DB tool extension SPI**: Added `DbToolHandler` so `DB` tools can be implemented per `tool_code` before fallback to legacy `ce_mcp_db_tool.sql_template`.
- **Knowledge graph handler**: Added optional `DbKnowledgeGraphToolHandler` (enabled with `convengine.mcp.db.knowledge.enabled=true`) that reads two consumer-owned metadata tables:
  - query knowledge catalog (scenario description + prepared SQL/API hints)
  - schema knowledge catalog (table/column semantics)
- **Similarity ranking output**: `DbKnowledgeGraphService` tokenizes user question and returns ranked `queryKnowledge` + `schemaKnowledge` + `insights` (`suggestedPreparedQueries`, `suggestedTables`) to help MCP answer similar/variant questions consistently.

### Cache Diagnostics & Proxy Hardening (v2.0.6)
- **Proxy hygiene**: `StaticConfigurationCacheService` now routes every helper through the proxied bean so `@Cacheable` saves the static `ce_*` collections instead of re-running SQL after warmup.
- **ConvEngineCacheAnalyzer + `/api/v1/cache/analyze`**: The new analyzer reports the active `CacheManager`, provider/resolver/interceptor/error-handler beans, Spring cache settings, `SimpleKey.EMPTY` entries, native entry counts, warmup timings, and AOP/advisor metadata for `StaticConfigurationCacheService`, `ConversationCacheService`, and `ConversationHistoryCacheService`.
- **Operational visibility**: Analyzer results highlight first-call versus second-call latencies, plus provider metadata, so you can prove caching is healthy in any deployment (demo, local, or company project) without guessing.

## Detailed Architecture Upgrades (v2.0.3 - v2.0.5)

### 1. Static Caching, Async Logging & Context RAG Evolution (v2.0.5)
- **Static Configurations Cache Loader**: All static framework properties (`ce_intent`, `ce_rule`, `ce_mcp_tool`, etc.) are pre-loaded entirely into memory on JVM live via `StaticTableCachePreloader`, dropping complex query I/O across `RulesStep` and `InteractionPolicyStep` to sub-millisecond evaluate times.
- **Cache Eviction API**: Deployed `/api/v1/cache/refresh` endpoint natively inside `ConvEngineCacheController` to allow database administrators to immediately flush and reload RAM without bouncing the application. 
- **Standalone Contextual Query Rewriting**: `DialogueActStep` now natively evaluates `convengine.flow.query-rewrite` directives. It intelligently piggybacks on the existing actuation LLM prompt, forcing it to seamlessly read `session.conversionHistory` and emit a mathematically perfect `"standaloneQuery"` search phrase, fundamentally eliminating downstream MCP RAG context drift while consuming zero additional network roundtrip latency.
- **Async LLM Call Logging**: Integrated `@Async` onto `LlmCallLogPersistenceService`. Lengthy physical HTTP prompts/completions are recorded in background threads preserving microsecond user-facing SLA times.
- **History DDL Revolution**: Hard-deprecated noisy legacy `role` & `stage` `ce_conversation_history` table arrays. Decoupled history updates away from the core `DbAuditService` pipeline loop entirely, deploying an explicit `user_input` + `assistant_output` monolithic string format that tracks asynchronously inside the final `PipelineEndGuardStep`.
- **JSON Payload Parameter Optimization**: Re-engineered `EngineSession.safeInputParams()` to aggressively strip deep duplicated objects (`CONTEXT`, `SCHEMA_JSON`, `SESSION_SUMMARY`) prior to JPA serialization, significantly condensing relational table footprints.
- **SQLite Timezone Idempotency**: Normalized all underlying timestamp extractions across standard JSON mapping using formal JVM `jackson-datatype-jsr310` deployments and rigorous `OffsetDateTime` string converters preserving micro-transaction timezone stability across disparate engine host OS profiles.

### 2. Caching, Async Persistence & Deep Audit
- **Spring `@Cacheable` + `@Async` Control Plane**: Persistences operations are now fire-and-forget background threads to erase RDBMS I/O latency, cleanly fronted by real-time synchronized cache managers. Enabled seamlessly via `@EnableConvEngineCaching` & `@EnableConvEngineAsyncConversation`.
- **`CacheInspectAuditStep`**: Dedicated `convengine.audit.cache-inspector` debug functionality to serialize the live hydrated cache properties to `ce_audit` as `CACHE_INSPECTION` payloads. 
- **Global `_cache` Logging**: `DbAuditService` captures memory footprints passively alongside all discrete stage checkpoints when inspection triggers are enabled.
- **Executor Macro History Control**: Reduced database redundant scans for conversational memory blocks by extracting provisioning arrays `historyProvider.lastTurns` into `DefaultConversationalEngine.process()`.

### 3. Robust Pending Action & Task Execution Lifecycle
- **Introduction of `ce_pending_action`**: A new core configuration table used to catalog multi-turn action candidates by intent, state, and `action_key`. Features indexed lookups `idx_ce_pending_action_lookup` across `enabled`, `action_key`, `intent_code`, and `state_code`.
- **`PendingActionRepository`**: Added Spring Data JPA queries focused on filtering eligible actions by phase, intent (`intentCode IS NULL OR ...`), state (`stateCode IS NULL OR ...`), and enabled status, ordering strictly by integer `priority`.
- **`CePendingAction` Entity**: A JPA entity mapping to `ce_pending_action` with `bean_name`, `method_names`, `priority`, `intent_code`, and `state_code` enabling highly parameterized late-binding invocations.
- **`ActionLifecycleStep`**: A top-level orchestration step responsible for managing the state transitions of modular pending actions within the conversation. Actions are stored in the session context as `IN_PROGRESS` pending definitive confirmation from the user. It evaluates configurable Time-To-Live parameters (`convengine.flow.action-lifecycle.ttl-turns` and `ttl-minutes`).
- **`PendingActionStep`**: A core execution step designed to finalize these actions. It routes the action to the corresponding `bean_name` and `method_names` if the user successfully confirms the action sequence.
- **`CeTaskExecutor`**: A specialized executor pattern designed to securely evaluate and invoke pending tasks synchronously within the strict temporal limits of the conversational turn.
- **Context Lifecycle Persistence**: The engine tracks the holistic lifecycle of these actions (`OPEN`, `IN_PROGRESS`, `EXECUTED`, `REJECTED`, `EXPIRED`) purely in the stringified JSON memory context map (`pending_action_runtime`), meticulously decoupled from the static table definition.
- **Disambiguation Matrix Controls**: Pending actions that are ambiguous or require explicit missing slot inputs before execution dynamically trigger the new disambiguation mechanisms to halt execution, stash the context, and query the user.

### 4. Deterministic Interaction Policies & Dialogue Acts
- **`DialogueActStep`**: A brand new classification pipeline step heavily augmenting the traditional NLP pass. Instead of relying purely on heavy LLM intent classification for simple operational conversational shifts, this step evaluates the user's raw input against fast strict Pattern Regex patterns (`AFFIRM`, `NEGATE`, `EDIT`, `RESET`) before triggering fallback heuristic LLM logic.
- **Dialogue Act Taxonomy**: Standardized ENUM classification integrated precisely into `ConvEngineInputParamKey.DIALOGUE_ACT` including `AFFIRM` (yes, proceed), `NEGATE` (no, cancel), `EDIT` (change something), `RESET` (start over), `QUESTION` (inquiry), and `NEW_REQUEST` (topic switch).
- **`DialogueActResolveMode` Enum**: Explicit execution evaluation modes (`REGEX_ONLY`, `REGEX_THEN_LLM`, `LLM_ONLY`) allowing the system to rapidly identify acts using extremely fast regex thresholds prior to paying LLM generation costs.
- **`InteractionPolicyStep` & `InteractionPolicyDecision`**: The primary "brain" of the v2 matrix routing implementation. This step evaluates the `DialogueAct` alongside the current discrete `SessionState`. For instance, if a pending action is marked `IN_PROGRESS` in memory, and the new turn evaluates to the act `AFFIRM`, it makes an instantaneous deterministic policy decision `EXECUTE_PENDING_ACTION`. If it evaluates to `NEGATE`, it selects `REJECT_PENDING_ACTION`. If it's `QUESTION`, it evaluates to `FILL_PENDING_SLOT`. This massive enhancement completely bypasses stochastic LLM generative behaviors for discrete boolean pathing.

### 5. Strict MCP Tool Management and Orchestration Scoping
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

### 6. Advanced Pipeline Governance: Memory, Guardrails, and Graphs
- **`GuardrailStep` Framework**: A foundational preventative pipeline step strategically positioned to screen dynamic inputs against hardcoded blocked configuration patterns or sanitize output payload injections before advancing. Generates robust metrics to soft-block or strictly hard-block the `ConvEngineInputParamKey.STATE_GRAPH_VALID` control parameter.
- **`DisambiguationStep` Integration**: An autonomous discrete evaluation system boundary. If multiple valid intents or competing multi-turn pending actions exhibit mathematically adjacent probabilities (Confidence score collisions), this step halts the internal execution state transitions and forces a deterministic query back to the end user for explicit disambiguation.
- **`StateGraphStep` Validation**: Built an internal compliance enforcement algorithm explicitly assessing whether the current proposed transition route from `State A -> State B` functionally complies with the statically mapped topologies inside the `convengine.flow.state-graph.allowed-transitions` logic limits.
- **`MemoryStep` and `ConversationMemoryStore` Context Modeling**: Engineered a long-term conversation compression service layer. The orchestration engine dynamically aggregates rolling conversation turns bounded by the `recent-turns-for-summary` threshold and compresses them into a highly compact `ConvEngineInputParamKey.MEMORY_SESSION_SUMMARY`. This drastically scales up context capacities, permitting the underlying LLM to seamlessly address sliding-window follow-up questions without wasting tokens continuously re-parsing huge historical payload stacks.

### 7. Deterministic Evaluation and Replay CI/CD Logics
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
- Database-driven verbose progress/error messaging (`ce_verbose`)
- Full audit timeline and trace API
- SSE and STOMP streaming support (`AUDIT` + `VERBOSE` envelope types)

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
5. Interaction policy decision
6. Action lifecycle/disambiguation/guardrail
7. Intent resolution + fallback/reset handling
8. Container data and MCP/tool orchestration
9. Schema extraction + auto-advance facts
10. Rules execution (multi-pass)
11. State graph validation (validate mode)
12. Response resolution
13. Memory update
14. Persist + pipeline end guard

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
- `ce_mcp_planner`
- `ce_mcp_db_tool`
- `ce_verbose`
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
- `phase`: `PRE_RESPONSE_RESOLUTION`, `POST_AGENT_INTENT`, `POST_AGENT_MCP`, `POST_TOOL_EXECUTION`
- `action`: `SET_INTENT`, `SET_STATE`, `SET_JSON`, `GET_CONTEXT`, `GET_SCHEMA_JSON`, `GET_SESSION`, `SET_TASK`
- `state_code`: `ANY`, `UNKNOWN`, or exact state

### `ce_intent_classifier`

- `rule_type`: `REGEX`, `CONTAINS`, `STARTS_WITH`

## Flow Configuration (application.yml)

Flow behavior is file-configured from consumer app config.

```yaml
convengine:
  mcp:
    db:
      knowledge:
        enabled: false
        tool-code: db.knowledge.graph
        query-catalog-table: ce_mcp_query_knowledge
        schema-catalog-table: ce_mcp_schema_knowledge
    http-api:
      defaults:
        connect-timeout-ms: 2000
        read-timeout-ms: 5000
        max-attempts: 2
        initial-backoff-ms: 200
        max-backoff-ms: 2000
        backoff-multiplier: 2.0
        circuit-breaker-enabled: true
        circuit-failure-threshold: 5
        circuit-open-ms: 30000
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
- Canonical MCP example seed packs:
  - `src/main/resources/sql/mcp_planner_seed.sql` (Postgres)
  - `src/main/resources/sql/mcp_planner_seed_postgres.sql` (Postgres alias)
  - `src/main/resources/sql/mcp_planner_seed_sqlite.sql` (SQLite)
- Optional advanced DB knowledge seed packs:
  - `src/main/resources/sql/seed_mcp_advanced_postgres.sql`
  - `src/main/resources/sql/seed_mcp_advanced_sqlite.sql`

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
    cache-inspector: false
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
