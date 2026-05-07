-- ============================================================
-- ConvEngine: rename ce_mcp_* tables/indexes to ce_agent_*
-- Run once on any existing database after deploying v2 code.
-- All statements are DDL only — no data is modified.
-- ============================================================

-- 1. Drop foreign key from ce_mcp_db_tool before renaming
--    (PostgreSQL names the FK automatically; adjust if your DB used a custom name)
-- ALTER TABLE ce_mcp_db_tool DROP CONSTRAINT IF EXISTS ce_mcp_db_tool_tool_id_fkey;

-- 2. Rename tables
ALTER TABLE ce_mcp_db_tool              RENAME TO ce_agent_db_tool;
ALTER TABLE ce_mcp_user_query_knowledge RENAME TO ce_agent_query_knowledge;
ALTER TABLE ce_mcp_user_feedback        RENAME TO ce_agent_feedback;
ALTER TABLE ce_mcp_planner              RENAME TO ce_agent_planner;
ALTER TABLE ce_mcp_tool                 RENAME TO ce_agent_tool;

-- 3. Rename indexes (PostgreSQL)
ALTER INDEX IF EXISTS idx_ce_mcp_tool_enabled
    RENAME TO idx_ce_agent_tool_enabled;

ALTER INDEX IF EXISTS idx_ce_mcp_planner_scope
    RENAME TO idx_ce_agent_planner_scope;

ALTER INDEX IF EXISTS idx_ce_mcp_user_query_knowledge_query_text
    RENAME TO idx_ce_agent_query_knowledge_query_text;

-- 4. Recreate the foreign key on the renamed table (if you dropped it in step 1)
-- ALTER TABLE ce_agent_db_tool
--     ADD CONSTRAINT ce_agent_db_tool_tool_id_fkey
--     FOREIGN KEY (tool_id) REFERENCES ce_agent_tool(tool_id);

-- ============================================================
-- SQLite note:
--   SQLite does not support ALTER INDEX ... RENAME.
--   The table RENAME TO statements above work as-is on SQLite.
--   Indexes are automatically moved with the table; no index rename needed.
-- ============================================================

-- Oracle note:
--   Oracle uses RENAME <old> TO <new> (no ALTER TABLE prefix).
--   RENAME ce_mcp_db_tool              TO ce_agent_db_tool;
--   RENAME ce_mcp_user_query_knowledge TO ce_agent_query_knowledge;
--   RENAME ce_mcp_user_feedback        TO ce_agent_feedback;
--   RENAME ce_mcp_planner              TO ce_agent_planner;
--   RENAME ce_mcp_tool                 TO ce_agent_tool;
--   Indexes must be renamed with ALTER INDEX <old> RENAME TO <new>.
-- ============================================================
