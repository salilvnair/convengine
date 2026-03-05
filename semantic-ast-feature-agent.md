# Semantic AST Feature Agent Guide

This guide is for requesting and implementing new features in ConvEngine semantic query mode with low ambiguity and production-safe output.

## Scope

Applies to:
- `mcp.db.query.mode = semantic`
- Semantic pipeline under:
  - `com.github.salilvnair.convengine.engine.mcp.query.semantic`

Goals:
- Keep AST versioned and backward compatible
- Keep compiler deterministic and parameterized
- Keep validation strict and fail-fast
- Keep extensibility via adapters/interceptors
- Keep seeds/DML/tests/docs in sync

---

## Current Architecture (Mental Model)

Flow:
1. Relevant table/entity retrieval
2. Schema graph traversal + join path
3. LLM AST generation (JSON only)
4. AST normalize
5. AST semantic validate
6. AST plan (table/join resolution)
7. SQL compile (parameterized)
8. SQL guardrail
9. SQL execute
10. Result summary

Core principle:
- LLM proposes AST, engine owns correctness.

---

## If You Need a New Feature: How to Ask LLM

Always ask in this structure:
1. Feature intent
2. User-query examples
3. AST contract delta
4. Validator behavior
5. Compiler behavior
6. Pipeline/audit/verbose expectations
7. Seed + DML requirements
8. Test matrix expectations
9. Rollback/fallback requirement

Use this exact prompt template:

```text
We are extending ConvEngine semantic-query (Spring Boot).

Current architecture:
- AST versioned: SemanticQueryAstV1 -> CanonicalAst
- Pipeline: normalize -> validate -> plan -> compile -> guardrail -> execute
- Package root: com.github.salilvnair.convengine.engine.mcp.query.semantic

I need a new feature: <FEATURE_NAME>

Business examples:
1) "<example question 1>"
2) "<example question 2>"
3) "<example question 3>"

Requirements:
- Backward compatible with astVersion=v1
- Add new AST fields under SemanticQueryAstV1 and canonical mapper
- Strict validation rules (fail-fast with explicit errors)
- Deterministic SQL compile (parameterized only, no string concat)
- Keep adapter/interceptor design (default impl @Order LOWEST_PRECEDENCE)
- Add audit + verbose determinants per stage
- Add ce_config/seed DML updates for prompts/schema if needed
- Add/adjust tests (unit + matrix + runtime)

Please return in this exact format:
A) Design summary (what changes and why)
B) AST schema delta (JSON snippet)
C) Java file-level patch plan (by class name + method)
D) Validation rules list
E) SQL compiler rules list
F) Determinants/audit additions
G) Seed + DML SQL (Postgres + SQLite)
H) Test plan (test names + assertions)
I) Risks and rollback plan
```

If you want implementation directly:

```text
Implement now. Give complete patch-ready code blocks and SQL.
```

---

## Mandatory Design Rules for Any New Feature

### 1) AST version safety
- Keep `astVersion` explicit (currently `v1`)
- Do not break existing fields
- New fields must be optional unless mandatory by spec

### 2) Canonical mapping
- All V1 additions must map into canonical AST
- Compiler/validator should read canonical AST, not raw V1

### 3) Validation first
- Unknown entity/field/metric/operator -> fail
- Type mismatch -> fail
- Empty logical groups -> fail
- Invalid sort/window/having semantics -> fail

### 4) Deterministic SQL
- Parameterized SQL only
- No direct interpolation of user values
- SQL shape from planner + semantic model + AST only

### 5) Guardrails
- SELECT-only
- Allowed tables only
- Limit cap enforced
- No DDL/DML statements

### 6) Extensibility
- Interface + default impl
- Default impl with lowest precedence where applicable
- Interceptor hook points for producer overrides

### 7) Operability
- Audit determinant coverage per stage
- Verbose determinant mapping
- Rich `_meta` details for debugging

---

## Recommended File Touchpoints (Checklist)

When adding a feature, verify each area:

### AST model
- `ast/SemanticQueryAstV1.java`
- New AST node classes under `ast/`

### Canonical model + mapper
- `ast/canonical/*`
- `ast/version/V1CanonicalAstMapper.java`

### Normalize/validate
- `ast/DefaultAstNormalizer.java`
- `ast/DefaultAstValidator.java`

### Planner
- `graph/DefaultAstPlanner.java`

### Compiler + guardrail
- `sql/DefaultSemanticSqlCompiler.java`
- `sql/SemanticSqlPolicyValidator.java`

### Runtime stage events
- stage providers in `runtime/stage/provider/*`
- audit/verbose determinant keys

### LLM generator prompt/schema config
- `DefaultSemanticAstGenerator`
- `ce_config` seeds:
  - `src/main/resources/sql/seed.sql`
  - `src/main/resources/sql/seed_postgres.sql`
  - `src/main/resources/sql/seed_sqlite.sql`
  - zapper seed files under `src/main/resources/sql/zapper/`

### Tests
- Generator tests
- Validator/compiler matrix tests
- Runtime stage tests
- End-to-end semantic example tests

---

## Feature Request Quality Bar (What to Provide)

Before asking for implementation, provide:
- 3-10 real user questions
- expected AST sample
- expected SQL shape (not exact SQL if flexible)
- strict/non-strict behavior preference
- failure behavior preference (hard fail vs soft fallback)

This reduces hallucinated contracts and rework.

---

## Example: Request for Embedding-backed Retrieval

```text
Implement now:
1) semantic-layer.yaml tuning for field synonyms and alias mapping to reduce AST validation misses
2) embedding-backed retrieval adapter (pgvector) with fallback to deterministic retrieval

Constraints:
- Keep interfaces so Neo4j/vector adapters can be added later
- Default adapter + interceptor extension points
- No breaking changes to existing semantic-catalog/dbkg modes
- Add tests and seed/DML if config keys needed
```

Expected technical output:
- New retrieval interface + default deterministic impl
- Pgvector retrieval adapter
- configurable ranking blend (deterministic + vector score)
- fallback behavior when vectors unavailable

---

## Example: Request for New AST Capability

Feature:
- `date_bucket` grouping (`day|week|month`)

Expected additions:
- AST field for bucket
- validator rules for bucket + timestamp fields
- compiler translation to DB-specific expression
- tests for grouping + sorting + having with bucket

---

## Common Failure Patterns (Avoid)

1. LLM invents unknown entities/fields
- Fix by passing explicit selected entity fields + synonyms in user/system prompt

2. AST valid JSON but semantically wrong
- Fix by strict validator and better schema with enums/required objects

3. SQL references aliases/tables not joined
- Fix by planner-owned join resolution and compile from planned tables only

4. Drift between code and DB config
- Fix by always shipping seed + DML together

5. Regression from feature additions
- Fix with matrix tests and negative tests first

---

## Required Output Contract for Any Feature PR

PR must include:
1. Code changes
2. Seed changes
3. DML script (Postgres + SQLite)
4. Unit tests
5. Runtime/flow test updates
6. Short migration notes

---

## Quick “Implement Now” Prompt (Short Version)

```text
Implement semantic-query feature: <FEATURE>.
Keep astVersion=v1 compatible.
Update V1 AST + canonical mapper + validator + planner + compiler + guardrail.
Add interceptors/default impl with override support.
Update ce_config schema prompt + seed files + provide Postgres/SQLite DML.
Add matrix tests + runtime stage tests + end-to-end semantic example test.
Return changed files list and test command output.
```

---

## Final Recommendation

For every new capability:
- write AST contract first,
- validator rules second,
- compiler semantics third,
- prompts/config fourth,
- tests first-class (not afterthought).

That order keeps semantic-query stable as features grow.

