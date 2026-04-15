-- DB SQL preflight + reconcile prompt config for PostgresQueryToolHandler
-- Load this DML if you want ce_config-driven prompt overrides for SQL preflight repair/reconcile.

INSERT INTO ce_config(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES (
    9101,
    'PostgresQueryToolHandler',
    'DB_SQL_PREFLIGHT_SYSTEM_PROMPT',
    'You are a DB SQL preflight repair assistant for ConvEngine MCP DB tools.
Repair only read-only SELECT SQL.
Use runtime schema metadata and semantic hints as source of truth.
Never invent unknown table/column names.
Keep named params unchanged when possible.
Return SQL ONLY.',
    true,
    CURRENT_TIMESTAMP
)
ON CONFLICT (config_type, config_key) DO UPDATE
SET config_value = EXCLUDED.config_value,
    enabled = EXCLUDED.enabled;

INSERT INTO ce_config(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES (
    9103,
    'PostgresQueryToolHandler',
    'DB_SQL_PREFLIGHT_SCHEMA_JSON',
    '{
  "type":"object",
  "required":["sql"],
  "properties":{
    "sql":{"type":"string"}
  },
  "additionalProperties":false
}',
    true,
    CURRENT_TIMESTAMP
)
ON CONFLICT (config_type, config_key) DO UPDATE
SET config_value = EXCLUDED.config_value,
    enabled = EXCLUDED.enabled;

INSERT INTO ce_config(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES (
    9102,
    'PostgresQueryToolHandler',
    'DB_SQL_PREFLIGHT_USER_PROMPT',
    'Original SQL:
{{sql_before}}

Params:
{{params_json}}

Runtime schema details:
{{schema_json}}

Semantic hints:
{{semantic_json}}

LLM Context JSON:
{{context_json}}

Execution error:
{{error_message}}',
    true,
    CURRENT_TIMESTAMP
)
ON CONFLICT (config_type, config_key) DO UPDATE
SET config_value = EXCLUDED.config_value,
    enabled = EXCLUDED.enabled;

INSERT INTO ce_config(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES (
    9104,
    'PostgresQueryToolHandler',
    'DB_SQL_RECONCILE_SYSTEM_PROMPT',
    'You are a DB SQL schema/type reconciliation assistant for ConvEngine MCP DB tools.
Validate SQL against provided semantic metadata and runtime DB schema.
Focus on type-safe predicates and parameter compatibility.
Keep query semantics unchanged.
Never invent table/column names.
For numeric columns, prefer CAST(:param AS <numeric-type>) when ambiguity can happen.
For transition-log outputs, deduplicate to one row per (request_id, scenario_id) using DISTINCT ON.
Use deterministic ordering with request_id, scenario_id, to_logged_at DESC, to_log_id DESC.
Preserve named params and return JSON only.',
    true,
    CURRENT_TIMESTAMP
)
ON CONFLICT (config_type, config_key) DO UPDATE
SET config_value = EXCLUDED.config_value,
    enabled = EXCLUDED.enabled;

INSERT INTO ce_config(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES (
    9105,
    'PostgresQueryToolHandler',
    'DB_SQL_RECONCILE_USER_PROMPT',
    'Candidate SQL:
{{sql_before}}

Params:
{{params_json}}

Preflight diagnostics:
{{preflight_json}}

Runtime schema details:
{{schema_json}}

Semantic hints:
{{semantic_json}}

Task:
- Fix type mismatches and unsafe comparisons.
- Keep joins/filters semantics unchanged.
- Enforce dedup for transition logs: one row per (request_id, scenario_id) via DISTINCT ON + deterministic ordering.
- Keep params unchanged.
- Return SQL only.',
    true,
    CURRENT_TIMESTAMP
)
ON CONFLICT (config_type, config_key) DO UPDATE
SET config_value = EXCLUDED.config_value,
    enabled = EXCLUDED.enabled;

INSERT INTO ce_config(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES (
    9106,
    'PostgresQueryToolHandler',
    'DB_SQL_RECONCILE_SCHEMA_JSON',
    '{
  "type":"object",
  "required":["sql"],
  "properties":{
    "sql":{"type":"string"}
  },
  "additionalProperties":false
}',
    true,
    CURRENT_TIMESTAMP
)
ON CONFLICT (config_type, config_key) DO UPDATE
SET config_value = EXCLUDED.config_value,
    enabled = EXCLUDED.enabled;
