# ConvEngine Java

ConvEngine Java is a deterministic conversational workflow engine with a database-first control plane.

It is built for auditable, enterprise workflows where intent resolution, schema collection, state transitions, and responses must be explicit and configurable.

## Architectural Goals

- deterministic execution over free-form behavior
- configuration-driven workflow semantics
- strict state and schema control
- postmortem-friendly observability
- framework extensibility (MCP, container data)

## System Architecture

### 1) API Layer
- Accepts user input + conversation id.
- Builds engine context.
- Delegates to engine pipeline.

### 2) Engine Orchestration Layer
- Ordered step execution.
- Each step has narrow responsibility.
- Stops only on terminal condition or hard error.

### 3) Resolver Layer
- intent resolvers (classifier + agent)
- collision resolver
- rule evaluation resolvers
- response type and output format resolvers
- MCP planner/executor

### 4) Persistence Layer
- JPA/Hibernate-backed `ce_*` tables
- conversation + audit + metadata all persisted

### 5) LLM Integration Layer
- OpenAI provider (or equivalent adapter)
- strict JSON extraction/intent contracts
- all invocations audited

## Execution Pipeline (Reference)

1. Load/Create conversation
2. Persist bootstrap
3. Audit user input
4. Intent resolution
5. Fallback intent/state
6. Add container data (if configured)
7. MCP tool step (if configured)
8. Schema extraction
9. Auto-advance facts
10. Rule evaluation
11. Response resolution
12. Persist conversation

## Data Control Plane (`ce_*`)

Core tables and role:

- `ce_intent`: allowed intents and hints
- `ce_intent_classifier`: deterministic intent patterns
- `ce_output_schema`: extraction schemas by intent/state
- `ce_prompt_template`: prompt templates keyed by `response_type`
- `ce_response`: response mapping by intent/state
- `ce_rule`: transition and context mutation rules
- `ce_conversation`: current runtime state
- `ce_audit`: stage-level event stream
- `ce_mcp_tool`: MCP tool registry
- `ce_mcp_db_tool`: SQL-backed MCP tool definitions
- `ce_config`: runtime config for resolver prompts/thresholds

## Prompting Model

Prompt sources:

1. `ce_config` for engine-level resolvers:
- intent agent
- intent collision resolver
- MCP planner

2. `ce_prompt_template` for extraction and response derivation:
- `response_type=SCHEMA_EXTRACTED_DATA`
- `response_type=TEXT`
- `response_type=JSON`

## Intent Model

Intent resolution combines:
- classifier match
- LLM agent scoring (`intentScores`)

If score gap is small:
- engine enters `INTENT_COLLISION`
- collision resolver asks disambiguation question

If top intent is clear:
- engine continues with selected intent/state

## Schema and State Model

When schema is configured:
- extract strict JSON from user turn
- merge into conversation context
- compute:
    - `schemaComplete`
    - `hasAnySchemaValue`

State transitions are applied by rules, not by hardcoded domain logic.

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
- `RESOLVE_INTENT` (marker)

## Response Resolution

Selection:
- `ce_response` by `intent + state` (with fallback)

Resolution path:
- `EXACT` => direct payload
- `DERIVED` => render prompt and invoke format resolver (TEXT/JSON)

For collision state:
- dedicated collision resolver can short-circuit normal mapping.

## Observability and Postmortem

`ce_audit` is the operational source of truth.

Typical high-value stage events:
- `*_LLM_INPUT` / `*_LLM_OUTPUT`
- `SCHEMA_STATUS`
- `AUTO_ADVANCE_FACTS`
- `RULE_MATCHED`
- `SET_*` / `GET_*`
- `ASSISTANT_OUTPUT`
- `ENGINE_RETURN`
- `PIPELINE_TIMING`

## Example Flows

### Example 1: Greeting
User: `Hi`  
Outcome:
- intent resolves to greeting
- no schema collection
- mapped greeting response returned

### Example 2: Ambiguous intent collision
User asks a query that can map to two intents with near-equal scores.  
Outcome:
- `INTENT_COLLISION` state
- one disambiguation follow-up is asked
- next user turn resolves to one intent and flow continues

### Example 3: Identifier-required operational query
User asks for analysis without required identifier.  
Outcome:
- clarification question asks for missing identifier
- once provided, analysis path resumes deterministically

## Extending the Engine (Recommended)

1. Add/update intent metadata.
2. Add classifier patterns if needed.
3. Define output schema for collection flows.
4. Define rule transitions.
5. Define response mappings.
6. Add templates (`response_type`).
7. Add resolver config in `ce_config`.
8. Validate with multi-turn audit traces.

## Design Constraints

- avoid domain hardcoding in steps
- avoid hidden transitions in resolver code
- keep behavior declarative in DB
- keep LLM outputs constrained and audited

ConvEngine Java should be operated as a policy-driven workflow runtime, not as an unconstrained chat assistant.
