-- ============================================================
-- ConvEngine: rename ce_mcp_* tables/indexes → ce_agent_*
--             + migrate ce_verbose MCP event names → AGENT_*
--             + migrate ce_config MCP_TOOL_* keys  → AGENT_TOOL_*
--
-- Run ONCE on any existing database that was created before this
-- release.  New installs using the current ddl.sql / ddl_postgres.sql
-- / ddl_sqlite.sql already use ce_agent_* names and do NOT need
-- this script.
--
-- Section A: DDL  — rename tables and indexes from ce_mcp_* to ce_agent_*
-- Section B: DML  — migrate ce_verbose step_match / step_value
-- Section C: DML  — migrate ce_config MCP_TOOL_* keys
-- ============================================================

-- ============================================================
-- Section A: DDL — table and index renames (PostgreSQL syntax)
-- ============================================================

-- 1. Drop the foreign key on ce_mcp_db_tool before renaming
--    (PostgreSQL auto-names it; adjust the constraint name if yours differs)
-- ALTER TABLE ce_mcp_db_tool DROP CONSTRAINT IF EXISTS ce_mcp_db_tool_tool_id_fkey;

-- 2. Rename tables
ALTER TABLE ce_mcp_db_tool              RENAME TO ce_agent_db_tool;
ALTER TABLE ce_mcp_user_query_knowledge RENAME TO ce_agent_query_knowledge;
ALTER TABLE ce_mcp_user_feedback        RENAME TO ce_agent_user_feedback;
ALTER TABLE ce_mcp_planner              RENAME TO ce_agent_planner;
ALTER TABLE ce_mcp_tool                 RENAME TO ce_agent_tool;

-- 3. Rename indexes (PostgreSQL)
ALTER INDEX IF EXISTS idx_ce_mcp_tool_enabled
    RENAME TO idx_ce_agent_tool_enabled;

ALTER INDEX IF EXISTS idx_ce_mcp_planner_scope
    RENAME TO idx_ce_agent_planner_scope;

ALTER INDEX IF EXISTS idx_ce_mcp_user_query_knowledge_query_text
    RENAME TO idx_ce_agent_query_knowledge_query_text;

-- 4. Recreate the foreign key after rename (uncomment if you dropped it in step 1)
-- ALTER TABLE ce_agent_db_tool
--     ADD CONSTRAINT ce_agent_db_tool_tool_id_fkey
--     FOREIGN KEY (tool_id) REFERENCES ce_agent_tool(tool_id) ON DELETE CASCADE;

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
--   RENAME ce_mcp_user_feedback        TO ce_agent_user_feedback;
--   RENAME ce_mcp_planner              TO ce_agent_planner;
--   RENAME ce_mcp_tool                 TO ce_agent_tool;
--   Indexes must be renamed with ALTER INDEX <old> RENAME TO <new>.
-- ============================================================


-- ============================================================
-- Section B: DML — migrate ce_verbose MCP event names → AGENT_*
--
-- AgentToolStep (formerly McpToolStep) publishes verbose events
-- using string keys.  Prior to this release those keys were
-- prefixed MCP_*.  They are now prefixed AGENT_*.  Any ce_verbose
-- rows configured with the old keys must be updated so they
-- continue to match at runtime.
--
-- Columns affected:
--   step_match  — pipeline step name used for matching
--   step_value  — verbose event type published by the step
--
-- Run AFTER Section A.
-- ============================================================

-- 5. step_match: McpToolStep → AgentToolStep
UPDATE ce_verbose
SET    step_match = 'AgentToolStep'
WHERE  step_match = 'McpToolStep';

-- 6. step_value: MCP_* → AGENT_*
UPDATE ce_verbose SET step_value = 'AGENT_TOOL_CALL'
WHERE  step_value = 'MCP_TOOL_CALL';

UPDATE ce_verbose SET step_value = 'AGENT_TOOL_RESULT'
WHERE  step_value = 'MCP_TOOL_RESULT';

UPDATE ce_verbose SET step_value = 'AGENT_TOOL_ERROR'
WHERE  step_value = 'MCP_TOOL_ERROR';

UPDATE ce_verbose SET step_value = 'AGENT_FINAL_ANSWER'
WHERE  step_value = 'MCP_FINAL_ANSWER';

UPDATE ce_verbose SET step_value = 'AGENT_DUPLICATE_TOOL_CALL_SUPPRESSED'
WHERE  step_value = 'MCP_DUPLICATE_TOOL_CALL_SUPPRESSED';

UPDATE ce_verbose SET step_value = 'AGENT_TOOL_ROOT_CAUSE'
WHERE  step_value = 'MCP_TOOL_ROOT_CAUSE';

-- Verification (0 rows = clean):
--   SELECT step_match, step_value, COUNT(*) AS n
--   FROM   ce_verbose
--   WHERE  step_match = 'McpToolStep' OR step_value LIKE 'MCP_%'
--   GROUP  BY step_match, step_value;


-- ============================================================
-- Section C: DML — migrate ce_config MCP_TOOL_* keys → AGENT_TOOL_*
--
-- AgentToolStep reads four runtime-tunable config keys from
-- ce_config.  Previously named MCP_TOOL_*, now AGENT_TOOL_*.
--
-- Keys affected:
--   MCP_TOOL_MAX_LOOPS              → AGENT_TOOL_MAX_LOOPS
--   MCP_TOOL_CALL_DELAY_MS          → AGENT_TOOL_CALL_DELAY_MS
--   MCP_TOOL_CALL_DELAY_AFTER_CALLS → AGENT_TOOL_CALL_DELAY_AFTER_CALLS
--   MCP_TOOL_CALL_DELAY_AFTER_MS    → AGENT_TOOL_CALL_DELAY_AFTER_MS
-- ============================================================

-- 7. ce_config key renames
UPDATE ce_config SET config_key = 'AGENT_TOOL_MAX_LOOPS'
WHERE  config_key = 'MCP_TOOL_MAX_LOOPS';

UPDATE ce_config SET config_key = 'AGENT_TOOL_CALL_DELAY_MS'
WHERE  config_key = 'MCP_TOOL_CALL_DELAY_MS';

UPDATE ce_config SET config_key = 'AGENT_TOOL_CALL_DELAY_AFTER_CALLS'
WHERE  config_key = 'MCP_TOOL_CALL_DELAY_AFTER_CALLS';

UPDATE ce_config SET config_key = 'AGENT_TOOL_CALL_DELAY_AFTER_MS'
WHERE  config_key = 'MCP_TOOL_CALL_DELAY_AFTER_MS';

-- Verification (0 rows = clean):
--   SELECT config_key FROM ce_config WHERE config_key LIKE 'MCP_TOOL_%';


-- ============================================================
-- Section D: DML — migrate ce_rule phase names + ce_agent_planner
--             prompt templates + ce_agent_user_feedback column
--
-- RulePhase enum renamed:
--   PRE_AGENT_MCP  → PRE_AGENT_TOOL
--   POST_AGENT_MCP → POST_AGENT_TOOL
--
-- ce_agent_planner prompts used template variables:
--   {{mcp_tools}}        → {{agent_tools}}
--   {{mcp_observations}} → {{agent_observations}}
--   "Available MCP tools:"    → "Available Agent tools:"
--   "Existing MCP observations:" → "Existing Agent observations:"
--
-- ce_agent_user_feedback column renamed:
--   mcp_tool_code → agent_tool_code
--
-- Run AFTER Sections A–C.
-- ============================================================

-- 8. ce_rule phase column migration
UPDATE ce_rule SET phase = 'POST_AGENT_TOOL'
WHERE  phase IN ('POST_AGENT_MCP', 'AGENT_POST_MCP');

UPDATE ce_rule SET phase = 'PRE_AGENT_TOOL'
WHERE  phase = 'PRE_AGENT_MCP';

-- Verification (0 rows = clean):
--   SELECT phase, COUNT(*) FROM ce_rule
--   WHERE phase IN ('POST_AGENT_MCP','AGENT_POST_MCP','PRE_AGENT_MCP')
--   GROUP BY phase;

-- 9. ce_agent_planner system_prompt template variable migrations
UPDATE ce_agent_planner
SET    system_prompt = REPLACE(system_prompt, 'MCP planning agent', 'Agent planning agent')
WHERE  system_prompt LIKE '%MCP planning agent%';

UPDATE ce_agent_planner
SET    system_prompt = REPLACE(system_prompt, '{{mcp_tools}}', '{{agent_tools}}')
WHERE  system_prompt LIKE '%{{mcp_tools}}%';

UPDATE ce_agent_planner
SET    system_prompt = REPLACE(system_prompt, '{{mcp_observations}}', '{{agent_observations}}')
WHERE  system_prompt LIKE '%{{mcp_observations}}%';

UPDATE ce_agent_planner
SET    system_prompt = REPLACE(system_prompt, 'Available MCP tools:', 'Available Agent tools:')
WHERE  system_prompt LIKE '%Available MCP tools:%';

UPDATE ce_agent_planner
SET    system_prompt = REPLACE(system_prompt, 'Existing MCP observations:', 'Existing Agent observations:')
WHERE  system_prompt LIKE '%Existing MCP observations:%';

-- 10. ce_agent_planner user_prompt template variable migrations
UPDATE ce_agent_planner
SET    user_prompt = REPLACE(user_prompt, '{{mcp_tools}}', '{{agent_tools}}')
WHERE  user_prompt LIKE '%{{mcp_tools}}%';

UPDATE ce_agent_planner
SET    user_prompt = REPLACE(user_prompt, '{{mcp_observations}}', '{{agent_observations}}')
WHERE  user_prompt LIKE '%{{mcp_observations}}%';

UPDATE ce_agent_planner
SET    user_prompt = REPLACE(user_prompt, 'Available MCP tools:', 'Available Agent tools:')
WHERE  user_prompt LIKE '%Available MCP tools:%';

UPDATE ce_agent_planner
SET    user_prompt = REPLACE(user_prompt, 'Existing MCP observations:', 'Existing Agent observations:')
WHERE  user_prompt LIKE '%Existing MCP observations:%';

-- Verification (0 rows = clean):
--   SELECT planner_id FROM ce_agent_planner
--   WHERE system_prompt LIKE '%{{mcp_%' OR user_prompt LIKE '%{{mcp_%'
--      OR system_prompt LIKE '%MCP planning%' OR user_prompt LIKE '%MCP planning%';

-- 11. ce_agent_user_feedback: rename mcp_tool_code column
--     PostgreSQL / H2 / MySQL syntax:
ALTER TABLE ce_agent_user_feedback RENAME COLUMN mcp_tool_code TO agent_tool_code;

--   SQLite does not support RENAME COLUMN before 3.25.0; for older SQLite use
--   the table-rebuild approach (create new table, copy, drop old, rename new).
--
--   Oracle syntax:
--     ALTER TABLE ce_agent_user_feedback RENAME COLUMN mcp_tool_code TO agent_tool_code;
--   (same DDL, no difference on Oracle 12.2+)

-- Verification:
--   SELECT column_name FROM information_schema.columns
--   WHERE table_name = 'ce_agent_user_feedback'
--   AND   column_name IN ('mcp_tool_code', 'agent_tool_code');
