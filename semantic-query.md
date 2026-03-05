# Semantic Query Engine Plan (ConvEngine)

This document captures the agreed plan for the new `semantic` DB query mode so work can continue safely if session context is lost.

## Goal

Add a third DB query mode in ConvEngine:

- existing: `semantic-catalog`
- existing: `knowledge-graph`
- new: `semantic`

The `semantic` mode must support:

1. Relevant table/entity retrieval
2. Schema graph traversal + join-path reasoning
3. LLM-generated SQL AST (JSON only, no direct SQL execution)
4. Deterministic SQL compilation
5. SQL validation + guardrail
6. DB execution + result mapping
7. Consumer extension via interceptor hooks

## Locked Config Contract

```yaml
convengine:
  mcp:
    db:
      query:
        mode: semantic # semantic-catalog | knowledge-graph | semantic

      semantic-catalog:
        enabled: true

      knowledge-graph:
        enabled: true

      semantic:
        enabled: true
        model-path: classpath:/mcp/semantic-layer.yaml
```

Rules:

1. `convengine.mcp.db.query.mode` is the single selector.
2. `semantic` properties stay under `convengine.mcp.db.semantic.*`.
3. Do not create a local `SemanticQueryMode` inside semantic package.
4. `SemanticQueryProperties` should represent only `mcp.db.semantic.*`.

## Package Boundary

All new code for this engine stays under:

`com.github.salilvnair.convengine.engine.mcp.query.semantic`

## Existing ConvEngine Integration Points

Re-use existing patterns/components:

1. `DbToolHandler` SPI
2. `McpDbToolExecutor` routing style
3. `McpSqlGuardrail` safety checks
4. `DbSchemaInspectorService` metadata/joins
5. Existing MCP handler/runtime style used by semantic-catalog and DBKG

## Canonical Schema Example Base

Use Zapper schema/seeds as the baseline for examples and tests:

- `src/main/resources/sql/zapper/ddl_postgres.sql`
- `src/main/resources/sql/zapper/zapper_business_seed.sql`

## Target Runtime Pipeline

1. User question
2. Relevant entity/table retrieval (lexical first, embedding pluggable)
3. Schema graph traversal
4. Join path generation
5. LLM generates AST JSON
6. AST validation + normalization
7. Deterministic SQL compile
8. SQL policy + guardrail validation
9. Database execution
10. Result mapping and optional summarization

## AST-First Contract

LLM must generate JSON AST only. SQL text is produced only by ConvEngine compiler.

Example AST shape:

```json
{
  "entity": "DisconnectRequest",
  "select": ["requestId", "accountId", "requestStatus"],
  "filters": [
    { "field": "requestStatus", "op": "=", "value": "FAILED" }
  ],
  "sort": [{ "field": "createdAt", "direction": "desc" }],
  "limit": 100
}
```

## Graph Adapter Strategy (Swappable)

Use a port/adapter pattern:

1. Own interface: `SchemaGraphEngine`
2. Default adapter now: JGraphT adapter
3. Future adapter: Neo4j adapter
4. Planner/resolver must depend only on interface, not JGraphT classes

Recommended config toggle:

```yaml
convengine:
  mcp:
    db:
      semantic:
        graph:
          adapter: jgrapht
```

## Suggested Semantic Module Layout

Inside `com.github.salilvnair.convengine.engine.mcp.query.semantic`:

1. `config` - semantic properties
2. `model` - semantic YAML POJOs
3. `load` - loader, validator, registry
4. `graph` - graph engine, join resolver
5. `ast` - AST models, validator, normalizer
6. `llm` - prompt builder, AST generation, repair
7. `plan` - planner and planning outputs
8. `sql` - compiler + policy validator
9. `execute` - executor + row mapping
10. `interceptor` - SPI + chain
11. `runtime` - orchestrator service
12. `handler` - semantic DB tool handler

## Interceptor Lifecycle (Consumer Extension)

Planned hooks:

1. `preResolve`
2. `postResolve`
3. `preAst`
4. `postAst`
5. `preCompile`
6. `preExecute`
7. `postExecute`
8. `onError`

Purpose:

- tenant filters
- extra policy checks
- masking
- custom audit/metrics

## Rollout Phases

### Phase A (Deterministic foundation)

1. semantic config + runtime wiring
2. semantic model load/validate/registry
3. schema graph + join path resolver
4. AST schema + validator + normalizer
5. deterministic SQL compiler
6. policy validator + guardrail + execute

### Phase B (LLM AST)

1. LLM prompt for AST generation
2. strict AST parse/validate
3. one repair attempt on invalid AST

### Phase C (Relevance quality)

1. lexical retrieval baseline
2. embedding rerank integration

### Phase D (Later)

1. endpoint/UI flow to generate semantic YAML draft from schema inspect popup

## Validation and Safety Requirements

1. SELECT-only execution
2. no DDL/DML
3. prepared parameters only
4. max limit clamp
5. allowed table checks
6. max join-depth limits
7. fail-fast on invalid entity/field/operator

## Notes from Semantic YAML Review

When full semantic YAML is used, validator must catch:

1. referenced table missing under `tables`
2. relationships referencing missing tables/columns
3. allowed-table mismatch
4. conflicting synonyms precedence
5. cardinality mismatches where explicitly modeled

## Current Status

Semantic-query foundation plus SQL execution path is implemented in this branch.

## Implementation Status (Current Branch)

The following runtime stages are now implemented:

1. Relevant Table/Entity Retrieval
2. Schema Graph Traversal + Join Path
3. LLM AST Generation (JSON only)
4. AST Validation
5. Deterministic SQL Compile
6. SQL Policy Validation + Guardrail
7. SQL Execution + Result Summary

Key implementation points:

1. All new semantic-query code lives under `com.github.salilvnair.convengine.engine.mcp.query.semantic`.
2. Every stage is interface-first with default lowest-priority implementation (`@Order(Ordered.LOWEST_PRECEDENCE)`), so consumers can override with higher priority beans.
3. Interceptor hooks were added for retrieval, graph, AST generation, AST validation, and runtime stage lifecycle.
4. Graph path uses adapter pattern (`SchemaGraphEngine`), with default JGraphT adapter now.
5. Retrieval supports deterministic + vector blending, and uses `LlmClient.generateEmbedding(EngineSession, String)`.
6. A new handler is added for semantic mode DB tool execution (`db.semantic.query`) behind `convengine.mcp.db.semantic.enabled=true`.
7. DAG ordering is extracted into generic core utility (`CoreStepDagOrderer`) and reused by both the main ConvEngine pipeline and semantic mini-pipeline.
8. Semantic runtime now includes pluggable SQL compiler, SQL policy validator, SQL executor, and result summarizer with ordered default implementations.
9. Semantic runtime output now includes compiled SQL, SQL params, execution rows/count, and summary.
10. Semantic runtime now emits stage-level audit and verbose events:
`SEMANTIC_STAGE_ENTER`, `SEMANTIC_STAGE_EXIT`, `SEMANTIC_STAGE_ERROR`, `SEMANTIC_RUNTIME_ERROR`.
11. AST generation now emits explicit LLM telemetry events:
`SEMANTIC_AST_LLM_INPUT`, `SEMANTIC_AST_LLM_OUTPUT`, `SEMANTIC_AST_LLM_ERROR` with `_meta` payload.
12. Additional dedicated semantic determinants are emitted:
`SEMANTIC_SCHEMA_GRAPH_TRAVERSED`, `SEMANTIC_JOIN_PATH_RESOLVED`,
`SEMANTIC_AST_GENERATED`, `SEMANTIC_AST_VALIDATED`.

## Local Verification Performed

1. `mvn -q -DskipTests compile` passed.
2. `mvn -q test` passed.

## Added Artifacts (This Branch)

1. Semantic model YAML (Zapper domain):
`src/main/resources/mcp/semantic-layer.yaml`
2. Semantic query MCP seed (Postgres):
`src/main/resources/sql/zapper/semantic_query_mcp_seed_postgres.sql`
3. Semantic query MCP seed (SQLite):
`src/main/resources/sql/zapper/semantic_query_mcp_seed_sqlite.sql`

## End-to-End Test Flow (Zapper)

### 1) Configure semantic mode

```yaml
convengine:
  mcp:
    db:
      query:
        mode: semantic
      semantic:
        enabled: true
        tool-code: db.semantic.query
        model-path: classpath:/mcp/semantic-layer.yaml
```

### 2) Load schema + business data + semantic MCP seed

Postgres order:

1. `src/main/resources/sql/zapper/ddl_postgres.sql`
2. `src/main/resources/sql/zapper/zapper_business_seed.sql`
3. `src/main/resources/sql/zapper/semantic_query_mcp_seed_postgres.sql`

SQLite order:

1. `src/main/resources/sql/zapper/ddl_sqlite.sql`
2. `src/main/resources/sql/zapper/zapper_business_seed.sql`
3. `src/main/resources/sql/zapper/semantic_query_mcp_seed_sqlite.sql`

### 3) Example prompts to validate pipeline

1. `show failed disconnect requests in last 24 hours`
2. `why did request ZPR1003 fail`
3. `show account status for UPSA100`
4. `list downstream checks for DON9001`

Rule-driven state behavior for semantic flow:

1. `UNKNOWN/IDLE -> ANALYZE` via `POST_AGENT_INTENT`
2. `ANALYZE -> FAILED` via `POST_AGENT_MCP` when `context.mcp.lifecycle` indicates error/blocked/fallback
3. `ANALYZE -> COMPLETED` via `POST_AGENT_MCP` when `context.mcp.finalAnswer` exists
4. `ANY -> IDLE` via `PRE_RESPONSE_RESOLUTION` reset command

### 4) Expected semantic runtime payload

The semantic DB tool payload now returns:

1. `retrieval`
2. `joinPath`
3. `ast`
4. `astValidation`
5. `compiledSql`
6. `compiledSqlParams`
7. `execution`
8. `summary`
9. `_meta` (ast/sql execution readiness flags and lifecycle metadata)
10. `next=completed`

## Notes

1. Semantic YAML loader is configured to ignore unknown YAML properties so richer semantic-layer files can evolve without breaking model load.
2. Runtime logic still consumes canonical semantic fields (`entities`, `tables`, `relationships`, `synonyms`) while additional metadata keys remain forward-compatible.
