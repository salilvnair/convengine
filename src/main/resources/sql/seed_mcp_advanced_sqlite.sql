-- ConvEngine 2.0.7: Advanced MCP seed (SQLite)
-- Purpose:
-- 1) Seed a DB knowledge-graph MCP tool (db.knowledge.graph)
-- 2) Seed a sample advanced HTTP_API tool (order.status.api)
-- 3) Seed query/schema knowledge catalogs used by DbKnowledgeGraphToolHandler

-- ---------------------------------------------------------------------------
-- 1) MCP tool registrations
-- ---------------------------------------------------------------------------
INSERT OR REPLACE INTO ce_mcp_tool (tool_id, tool_code, tool_group, intent_code, state_code, enabled, description, created_at)
VALUES
  (9001, 'db.knowledge.graph', 'DB', 'ANY', 'ANY', 1,
   'Semantic query/schema knowledge retrieval for similar issue diagnosis', CURRENT_TIMESTAMP),
  (9002, 'order.status.api', 'HTTP_API', 'ANY', 'ANY', 1,
   'Order status lookup via external API with retry/circuit/auth/mapping', CURRENT_TIMESTAMP);

-- No ce_mcp_db_tool row is required for db.knowledge.graph when handled by
-- DbToolHandler implementation.

-- ---------------------------------------------------------------------------
-- 2) Knowledge catalog tables
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ce_mcp_query_knowledge (
  id INTEGER PRIMARY KEY,
  query_text TEXT NOT NULL,
  description TEXT,
  prepared_sql TEXT,
  tags TEXT,
  api_hints TEXT
);

CREATE TABLE IF NOT EXISTS ce_mcp_schema_knowledge (
  id INTEGER PRIMARY KEY,
  table_name TEXT NOT NULL,
  column_name TEXT,
  description TEXT,
  tags TEXT
);

-- ---------------------------------------------------------------------------
-- 3) Query knowledge rows (use-case: async null/order submitted/admin/api3/api4)
-- ---------------------------------------------------------------------------
INSERT OR REPLACE INTO ce_mcp_query_knowledge (id, query_text, description, prepared_sql, tags, api_hints)
VALUES
  (
    10001,
    'Order submitted by admin but api4 async callback is null',
    'Find admin-submitted orders where async callback did not arrive after submission.',
    'SELECT o.order_id, o.submitted_by_role, o.status, o.api3_status, o.api4_async_status, o.updated_at\nFROM orders o\nWHERE o.submitted_by_role = ''ADMIN''\n  AND o.status = ''SUBMITTED''\n  AND (o.api4_async_status IS NULL OR o.api4_async_status = '''')\nORDER BY o.updated_at DESC\nLIMIT :limit;',
    'order,admin,submitted,async,null,api4',
    'api3,api4'
  ),
  (
    10002,
    'Compare api3 success with api4 async null mismatch',
    'Detect rows where api3 completed but api4 callback is missing.',
    'SELECT o.order_id, o.api3_status, o.api4_async_status, o.updated_at\nFROM orders o\nWHERE o.api3_status = ''SUCCESS''\n  AND (o.api4_async_status IS NULL OR o.api4_async_status = '''')\nORDER BY o.updated_at DESC\nLIMIT :limit;',
    'order,api3,api4,callback,mismatch',
    'api3,api4'
  ),
  (
    10003,
    'Track latency between order submitted and callback received',
    'Check delayed callbacks and identify potentially stuck async processing.',
    'SELECT o.order_id, o.created_at, o.callback_received_at,\n       CAST((julianday(o.callback_received_at) - julianday(o.created_at)) * 86400 AS INTEGER) AS callback_latency_seconds\nFROM orders o\nWHERE o.created_at >= datetime(''now'', ''-7 day'')\nORDER BY callback_latency_seconds DESC\nLIMIT :limit;',
    'order,latency,callback,delay',
    'api4'
  );

-- ---------------------------------------------------------------------------
-- 4) Schema knowledge rows (use-case: table + column semantics)
-- ---------------------------------------------------------------------------
INSERT OR REPLACE INTO ce_mcp_schema_knowledge (id, table_name, column_name, description, tags)
VALUES
  (11001, 'orders', 'order_id', 'Business order identifier used across APIs and callbacks.', 'order,id,primary-key'),
  (11002, 'orders', 'submitted_by_role', 'Actor role who submitted the order (ADMIN/USER/SYSTEM).', 'order,actor,role,admin'),
  (11003, 'orders', 'status', 'Primary order lifecycle state (DRAFT/SUBMITTED/PROCESSING/COMPLETED/FAILED).', 'order,status,lifecycle'),
  (11004, 'orders', 'api3_status', 'Synchronous upstream API result status for stage api3.', 'api3,status,upstream'),
  (11005, 'orders', 'api4_async_status', 'Asynchronous callback status from api4; NULL usually means callback pending/missing.', 'api4,async,status,callback,null'),
  (11006, 'orders', 'created_at', 'Order submit timestamp.', 'order,time,created'),
  (11007, 'orders', 'updated_at', 'Last order update timestamp.', 'order,time,updated'),
  (11008, 'orders', 'callback_received_at', 'Timestamp when async callback payload was received.', 'callback,time,api4');
