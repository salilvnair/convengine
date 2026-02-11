# ConvEngine (Java) — AGENT.md

## Overview

ConvEngine Java is a deterministic, database-driven conversational workflow engine.

It is not an open-ended chatbot runtime.  
It is a stateful orchestration engine that resolves intent, collects structured data, applies rule-driven transitions, and emits configured responses with full auditability.

---

## Core Principles

1. DB-driven control plane  
   Runtime behavior is defined in `ce_*` tables.

2. Deterministic pipeline  
   Requests run through ordered engine steps.

3. LLM as constrained subsystem  
   LLM is used for controlled classification, extraction, and derivation only.

4. Rule-based state progression  
   Intent/state transitions are governed by `ce_rule`.

---

## Runtime Pipeline

Typical step flow:
1. Load/Create conversation
2. Persist bootstrap
3. Audit user input
4. Intent resolution
5. Fallback intent/state (if needed)
6. Add container data (framework-specific)
7. MCP tool step (if configured and not blocked)
8. Schema extraction
9. Auto-advance facts
10. Rule evaluation
11. Response resolution
12. Persist conversation

Each stage emits audit events in `ce_audit`.

---

## Runtime Objects

### EngineContext
Immutable request envelope:
- `conversationId`
- `userText`
- `inputParams`

### EngineSession
Mutable conversation runtime:
- `intent`, `state`
- `contextJson`
- `resolvedSchema`
- `schemaComplete`, `hasAnySchemaValue`
- `pendingClarificationQuestion`, `pendingClarificationReason`
- `lastLlmOutput`, `lastLlmStage`
- `inputParams`

Supports audit-safe parameter snapshotting for postmortem.

---

## Prompt and Response Architecture

### Prompt source hierarchy
1. `ce_config` for system-level resolvers (`AgentIntentResolver`, collision resolver, MCP planner).
2. `ce_prompt_template` for response/extraction flows keyed by `response_type`.

### Response routing
`ce_response` mapping by `intent + state` with priority/fallback.

Response strategies:
- `EXACT`
- `DERIVED`

Derived responses delegate by `output_format` (TEXT/JSON) into format resolvers.

---

## Intent Resolution

Composite model:
- classifier resolver
- agent resolver

Agent resolver can return:
- top intent
- confidence
- intent score list
- collision state (`INTENT_COLLISION`)
- follow-up candidates

When collision is detected:
- dedicated collision resolver produces a disambiguation question
- response mapping path is short-circuited for that turn

---

## Schema Extraction

When `ce_output_schema` exists for current intent/state:
- strict JSON extraction runs
- extraction merges into `contextJson`
- missing required fields and options are computed
- schema facts are emitted:
    - `schemaComplete`
    - `hasAnySchemaValue`

---

## Rule Engine

Rule types:
- `AGENT`
- `REGEX`
- `EXACT`
- `JSON_PATH`

Actions:
- `SET_STATE`
- `SET_INTENT`
- `SET_JSON`
- `GET_CONTEXT`
- `GET_SESSION`
- `GET_SCHEMA_EXTRACTED_DATA`
- `SHORT_CIRCUIT`
- `RESOLVE_INTENT` (marker action)

Rule audits should include `ruleId`, state/intent, context, extracted data, and session input snapshots.

---

## MCP and Container Hooks

Java runtime may include:
- MCP planning/execution with DB-configured tools
- container data step (CCF integration)

These are framework hooks and should remain configurable, not hardcoded per domain.

---

## Persistence Tables

Primary tables:
- `ce_intent`
- `ce_intent_classifier`
- `ce_output_schema`
- `ce_prompt_template`
- `ce_response`
- `ce_rule`
- `ce_conversation`
- `ce_audit`
- `ce_mcp_tool`
- `ce_mcp_db_tool`
- `ce_config`

---

## Operational Guidance

1. Keep workflow semantics in DB config first.
2. Keep step code domain-agnostic.
3. Use audit payloads for root-cause debugging before changing logic.
4. Avoid hidden state transitions in resolver code.
5. Prefer explicit `ce_rule` transitions over conditional hardcoding.

---

ConvEngine Java should behave as a deterministic conversational workflow runtime whose business behavior is declared in data.
