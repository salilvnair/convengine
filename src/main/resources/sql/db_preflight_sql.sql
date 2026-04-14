-- DB SQL preflight prompt config for McpDbExecutor
-- Load this DML if you want ce_config-driven prompt overrides for SQL preflight repair.

INSERT INTO ce_config(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES (
    9101,
    'McpDbExecutor',
    'DB_SQL_PREFLIGHT_SYSTEM_PROMPT',
    'You are a DB SQL preflight repair assistant for ConvEngine MCP DB tools.
Repair only read-only SELECT SQL.
Use runtime schema metadata and semantic hints as source of truth.
Never invent unknown table/column names.
Keep named params unchanged when possible.
Return SQL ONLY.',
    true,
    CURRENT_TIMESTAMP
);

INSERT INTO ce_config(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES (
    9103,
    'McpDbExecutor',
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
);

INSERT INTO ce_config(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES (
    9102,
    'McpDbExecutor',
    'DB_SQL_PREFLIGHT_USER_PROMPT',
    'Original SQL:
{{sql_before}}

Params:
{{params_json}}

Runtime schema details:
{{schema_json}}

Semantic hints:
{{semantic_json}}

Execution error:
{{error_message}}',
    true,
    CURRENT_TIMESTAMP
);
