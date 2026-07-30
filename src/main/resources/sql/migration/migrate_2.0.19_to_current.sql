-- ============================================================================
-- ConvEngine full migration: prod (2.0.19 baseline) -> current main
-- Dialect: PostgreSQL
--
-- Consolidated, single-file version of everything found across this
-- migration effort:
--   PART 1 — ce_mcp_* -> ce_agent_* table/column/index rename (mandatory)
--   PART 2 — create missing semantic-v2 tables (idempotent)
--   PART 3 — drop the dead YAML-derived semantic layer (confirmed unused)
--   PART 4 — ce_mcp_server (new table, required for the MCP client)
--   PART 5 — context.mcp -> context.agent (critical DML fix, see notes)
--
-- BEFORE RUNNING: take a full DB backup / snapshot.
-- Safe to re-run in full: every statement is guarded (IF EXISTS/IF NOT
-- EXISTS) or keyed on old values, so a partial run can be re-applied.
-- ============================================================================

BEGIN;

-- ============================================================================
-- PART 1 — ce_mcp_* -> ce_agent_* rename + dependent data fixes
-- (the whole "MCP -> Agent tools" refactor, commit 3063269)
-- ============================================================================

-- 1a. Table renames
ALTER TABLE IF EXISTS ce_mcp_db_tool              RENAME TO ce_agent_db_tool;
ALTER TABLE IF EXISTS ce_mcp_user_query_knowledge RENAME TO ce_agent_query_knowledge;
ALTER TABLE IF EXISTS ce_mcp_user_feedback        RENAME TO ce_agent_user_feedback;
ALTER TABLE IF EXISTS ce_mcp_planner              RENAME TO ce_agent_planner;
ALTER TABLE IF EXISTS ce_mcp_tool                 RENAME TO ce_agent_tool;

-- 1b. Index renames
ALTER INDEX IF EXISTS idx_ce_mcp_tool_enabled
    RENAME TO idx_ce_agent_tool_enabled;
ALTER INDEX IF EXISTS idx_ce_mcp_planner_scope
    RENAME TO idx_ce_agent_planner_scope;
ALTER INDEX IF EXISTS idx_ce_mcp_user_query_knowledge_query_text
    RENAME TO idx_ce_agent_query_knowledge_query_text;
ALTER INDEX IF EXISTS idx_ce_mcp_user_feedback_conversation
    RENAME TO idx_ce_agent_user_feedback_conversation;
ALTER INDEX IF EXISTS idx_ce_mcp_db_tool_dialect
    RENAME TO idx_ce_agent_db_tool_dialect;

-- 1c. Column rename on the renamed feedback table
ALTER TABLE ce_agent_user_feedback RENAME COLUMN mcp_tool_code TO agent_tool_code;

-- 1d. ce_verbose: MCP event step names -> AGENT_*
UPDATE ce_verbose SET step_match = 'AgentToolStep' WHERE step_match = 'McpToolStep';

UPDATE ce_verbose SET step_value = 'AGENT_TOOL_CALL'                        WHERE step_value = 'MCP_TOOL_CALL';
UPDATE ce_verbose SET step_value = 'AGENT_TOOL_RESULT'                      WHERE step_value = 'MCP_TOOL_RESULT';
UPDATE ce_verbose SET step_value = 'AGENT_TOOL_ERROR'                       WHERE step_value = 'MCP_TOOL_ERROR';
UPDATE ce_verbose SET step_value = 'AGENT_FINAL_ANSWER'                     WHERE step_value = 'MCP_FINAL_ANSWER';
UPDATE ce_verbose SET step_value = 'AGENT_DUPLICATE_TOOL_CALL_SUPPRESSED'   WHERE step_value = 'MCP_DUPLICATE_TOOL_CALL_SUPPRESSED';
UPDATE ce_verbose SET step_value = 'AGENT_TOOL_ROOT_CAUSE'                  WHERE step_value = 'MCP_TOOL_ROOT_CAUSE';

-- 1e. ce_config: MCP_TOOL_* keys -> AGENT_TOOL_*
UPDATE ce_config SET config_key = 'AGENT_TOOL_MAX_LOOPS'                 WHERE config_key = 'MCP_TOOL_MAX_LOOPS';
UPDATE ce_config SET config_key = 'AGENT_TOOL_CALL_DELAY_MS'             WHERE config_key = 'MCP_TOOL_CALL_DELAY_MS';
UPDATE ce_config SET config_key = 'AGENT_TOOL_CALL_DELAY_AFTER_CALLS'    WHERE config_key = 'MCP_TOOL_CALL_DELAY_AFTER_CALLS';
UPDATE ce_config SET config_key = 'AGENT_TOOL_CALL_DELAY_AFTER_MS'       WHERE config_key = 'MCP_TOOL_CALL_DELAY_AFTER_MS';

-- 1f. ce_rule.phase: PRE_AGENT_MCP/POST_AGENT_MCP -> PRE_AGENT_TOOL/POST_AGENT_TOOL
UPDATE ce_rule SET phase = 'PRE_AGENT_TOOL'  WHERE phase = 'PRE_AGENT_MCP';
UPDATE ce_rule SET phase = 'POST_AGENT_TOOL' WHERE phase IN ('POST_AGENT_MCP', 'AGENT_POST_MCP');

-- 1g. ce_agent_planner prompt template variables: {{mcp_tools}} -> {{agent_tools}}, etc.
UPDATE ce_agent_planner
SET    system_prompt = REPLACE(system_prompt, 'MCP planning agent', 'Agent planning agent')
WHERE  system_prompt LIKE '%MCP planning agent%';

UPDATE ce_agent_planner
SET    system_prompt = REPLACE(REPLACE(REPLACE(REPLACE(system_prompt,
           '{{mcp_tools}}', '{{agent_tools}}'),
           '{{mcp_observations}}', '{{agent_observations}}'),
           'Available MCP tools:', 'Available Agent tools:'),
           'Existing MCP observations:', 'Existing Agent observations:')
WHERE  system_prompt LIKE '%{{mcp_tools}}%'
   OR  system_prompt LIKE '%{{mcp_observations}}%'
   OR  system_prompt LIKE '%Available MCP tools:%'
   OR  system_prompt LIKE '%Existing MCP observations:%';

UPDATE ce_agent_planner
SET    user_prompt = REPLACE(REPLACE(REPLACE(REPLACE(user_prompt,
           '{{mcp_tools}}', '{{agent_tools}}'),
           '{{mcp_observations}}', '{{agent_observations}}'),
           'Available MCP tools:', 'Available Agent tools:'),
           'Existing MCP observations:', 'Existing Agent observations:')
WHERE  user_prompt LIKE '%{{mcp_tools}}%'
   OR  user_prompt LIKE '%{{mcp_observations}}%'
   OR  user_prompt LIKE '%Available MCP tools:%'
   OR  user_prompt LIKE '%Existing MCP observations:%';


-- ============================================================================
-- PART 2 — create semantic-v2 tables that don't exist on prod yet
-- (idempotent: IF NOT EXISTS everywhere, safe even if some already exist)
-- Source: src/main/resources/mcp/semantic_v2/ddl.sql
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS ce_semantic_concept (
    id BIGSERIAL PRIMARY KEY,
    concept_key VARCHAR(255) NOT NULL,
    concept_kind VARCHAR(100) NOT NULL,
    description TEXT,
    tags VARCHAR(2000),
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ce_semantic_concept_key
    ON public.ce_semantic_concept USING btree (concept_key);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_concept_lookup
    ON public.ce_semantic_concept USING btree (enabled, concept_kind, priority);

CREATE TABLE IF NOT EXISTS ce_semantic_synonym (
    id BIGSERIAL PRIMARY KEY,
    synonym_text VARCHAR(500) NOT NULL,
    concept_key VARCHAR(255) NOT NULL,
    domain_key VARCHAR(255),
    confidence_score NUMERIC(5,4) DEFAULT 1.0000 NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_synonym_lookup
    ON public.ce_semantic_synonym USING btree (enabled, synonym_text, concept_key, priority);

CREATE TABLE IF NOT EXISTS ce_semantic_concept_embedding (
    id BIGSERIAL PRIMARY KEY,
    concept_key VARCHAR(255) NOT NULL,
    source_text TEXT NOT NULL,
    embedding_text jsonb,
    embedding_model VARCHAR(255),
    embedding_version VARCHAR(100),
    confidence_score NUMERIC(5,4) DEFAULT 1.0000 NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT uq_ce_semantic_concept_embedding UNIQUE (concept_key, priority)
);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_concept_embedding_lookup
    ON public.ce_semantic_concept_embedding USING btree (enabled, concept_key, priority);

CREATE TABLE IF NOT EXISTS ce_semantic_mapping (
    id BIGSERIAL PRIMARY KEY,
    concept_key VARCHAR(255) NOT NULL,
    entity_key VARCHAR(255) NOT NULL,
    field_key VARCHAR(255) NOT NULL,
    mapped_table VARCHAR(255) NOT NULL,
    mapped_column VARCHAR(255) NOT NULL,
    operator_type VARCHAR(100),
    value_map_json jsonb,
    query_class_key VARCHAR(255),
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT uq_ce_semantic_mapping UNIQUE (entity_key, field_key, query_class_key, priority)
);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_mapping_lookup
    ON public.ce_semantic_mapping USING btree (enabled, entity_key, field_key, query_class_key, priority);

CREATE TABLE IF NOT EXISTS ce_semantic_join_path (
    id BIGSERIAL PRIMARY KEY,
    left_entity_key VARCHAR(255) NOT NULL,
    right_entity_key VARCHAR(255) NOT NULL,
    join_expression TEXT NOT NULL,
    join_priority INTEGER DEFAULT 100 NOT NULL,
    confidence_score NUMERIC(5,4) DEFAULT 1.0000 NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_join_path_lookup
    ON public.ce_semantic_join_path USING btree (enabled, left_entity_key, right_entity_key, join_priority);

CREATE TABLE IF NOT EXISTS ce_semantic_query_class (
    id BIGSERIAL PRIMARY KEY,
    query_class_key VARCHAR(255) NOT NULL,
    description TEXT,
    base_table_name VARCHAR(255),
    ast_skeleton_json jsonb,
    allowed_filter_fields_json jsonb,
    default_select_fields_json jsonb,
    default_sort_fields_json jsonb,
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ce_semantic_query_class_key
    ON public.ce_semantic_query_class USING btree (query_class_key);

CREATE TABLE IF NOT EXISTS ce_semantic_ambiguity_option (
    id BIGSERIAL PRIMARY KEY,
    entity_key VARCHAR(120) NOT NULL,
    query_class_key VARCHAR(120) NOT NULL,
    ambiguity_code VARCHAR(120),
    field_key VARCHAR(120),
    option_key VARCHAR(120) NOT NULL,
    option_label VARCHAR(255) NOT NULL,
    mapped_filter_json jsonb NOT NULL,
    recommended BOOLEAN DEFAULT false NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ce_semantic_ambiguity_option
    ON public.ce_semantic_ambiguity_option USING btree
    (entity_key, query_class_key, COALESCE(ambiguity_code,''), COALESCE(field_key,''), option_key);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_ambiguity_option_lookup
    ON public.ce_semantic_ambiguity_option USING btree
    (enabled, entity_key, query_class_key, COALESCE(ambiguity_code,''), COALESCE(field_key,''), priority);

CREATE TABLE IF NOT EXISTS ce_semantic_query_failures (
    id BIGSERIAL PRIMARY KEY,
    conversation_id uuid,
    question TEXT NOT NULL,
    question_embedding vector,
    generated_sql TEXT,
    corrected_sql TEXT,
    root_cause_code VARCHAR(255),
    reason TEXT,
    stage_code VARCHAR(100),
    metadata_json jsonb,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_query_failures_created
    ON public.ce_semantic_query_failures USING btree (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_query_failures_embedding
    ON public.ce_semantic_query_failures USING ivfflat (question_embedding vector_cosine_ops) WITH (lists = 100);


-- ============================================================================
-- PART 3 — drop the dead YAML-derived semantic layer
-- Confirmed unused. The only code that ever touched these
-- (SemanticModelDynamicOverlayService, called once at app startup via
-- SemanticModelRegistry.@PostConstruct) wraps every query in try/catch and
-- silently degrades to empty on error — dropping the tables will not break
-- startup or any request path.
-- ============================================================================

DROP TABLE IF EXISTS ce_semantic_model             CASCADE;
DROP TABLE IF EXISTS ce_semantic_setting           CASCADE;
DROP TABLE IF EXISTS ce_semantic_source_column     CASCADE;
DROP TABLE IF EXISTS ce_semantic_source_table      CASCADE;
DROP TABLE IF EXISTS ce_semantic_lexicon           CASCADE;
DROP TABLE IF EXISTS ce_semantic_rule_allowed_table CASCADE;
DROP TABLE IF EXISTS ce_semantic_rule_deny_operation CASCADE;
DROP TABLE IF EXISTS ce_semantic_rule_config       CASCADE;
DROP TABLE IF EXISTS ce_semantic_intent_rule       CASCADE;
DROP TABLE IF EXISTS ce_semantic_metric            CASCADE;

-- Pre-semantic-v2 "override" tables: fold into the canonical names if the
-- canonical table doesn't already exist — otherwise just drop the leftover.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'ce_semantic_entity_override') THEN
        IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'ce_semantic_entity') THEN
            ALTER TABLE ce_semantic_entity_override RENAME TO ce_semantic_entity;
        ELSE
            DROP TABLE ce_semantic_entity_override CASCADE;
        END IF;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'ce_semantic_relationship_override') THEN
        IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'ce_semantic_relationship') THEN
            ALTER TABLE ce_semantic_relationship_override RENAME TO ce_semantic_relationship;
        ELSE
            DROP TABLE ce_semantic_relationship_override CASCADE;
        END IF;
    END IF;
END $$;


-- ============================================================================
-- PART 4 — ce_mcp_server (required for the MCP client / McpRegistry)
-- McpRegistry.serverRepository is a required constructor dependency — there
-- is NO local-file fallback (~/.convengine/mcp-servers.json doesn't exist
-- anymore). If this table is missing, McpRegistry logs an error and starts
-- with zero configs (app still boots), but MCP server register/list/call
-- won't work until this table exists.
-- ============================================================================

CREATE TABLE IF NOT EXISTS ce_mcp_server (
    id text NOT NULL,
    name text NULL,
    transport text NOT NULL,
    command text NULL,
    args jsonb NULL,
    env jsonb NULL,
    url text NULL,
    headers jsonb NULL,
    enabled bool DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT ce_mcp_server_pkey PRIMARY KEY (id),
    CONSTRAINT ce_mcp_server_transport_check CHECK (transport IN ('STDIO', 'HTTP', 'SSE'))
);
CREATE INDEX IF NOT EXISTS idx_ce_mcp_server_enabled ON public.ce_mcp_server USING btree (enabled);

-- OPTIONAL: one-time import of servers already sitting in a local
-- ~/.convengine/mcp-servers.json (pre-DB-backed installs). Example:
-- INSERT INTO ce_mcp_server (id, name, transport, command, args)
-- VALUES ('weather-srv', 'Weather MCP', 'STDIO', 'uvx', '["weather-mcp-server"]'::jsonb)
-- ON CONFLICT (id) DO UPDATE SET
--     name = EXCLUDED.name, transport = EXCLUDED.transport,
--     command = EXCLUDED.command, args = EXCLUDED.args, updated_at = now();


-- ============================================================================
-- PART 5 — context.mcp -> context.agent (critical DML fix)
--
-- Why this is critical (found by reading the runtime code, not just seed
-- files, after the mcp->agent rename in commit 3063269):
--
--   At 2.0.19, the agent-tool-execution context object was written under a
--   top-level JSON key literally named "mcp":
--     McpConstants.CONTEXT_KEY_MCP = "mcp"
--     contextJson: {"mcp":{"observations":[...],"finalAnswer":"..."}}
--
--   In the mcp->agent refactor this key was renamed to "agent":
--     AgentConstants.CONTEXT_KEY_AGENT = "agent"
--     contextJson: {"agent":{"observations":[...],"finalAnswer":"..."}}
--
--   Part 1 above only ever fixed the *placeholder variables*
--   ({{mcp_tools}}/{{mcp_observations}}) in ce_agent_planner prompts —
--   nothing previously fixed the literal "context.mcp." path references
--   baked into:
--     - ce_rule.match_pattern   (JSON_PATH rules, e.g. state transitions on
--                                tool completion — these SILENTLY STOP
--                                MATCHING after the key rename, since they
--                                test a path that no longer exists)
--     - ce_response.exact_text  (Thymeleaf templates —
--                                ${context.mcp.finalAnswer} silently
--                                renders blank instead of the real answer)
--     - ce_response.derivation_hint / ce_prompt_template / ce_agent_planner
--       prompts (prose instructions to the LLM — wrong path name confuses
--       rather than hard-breaks, but still wrong)
--
--   Not hypothetical: three call sites in the ConvEngine codebase itself
--   had this exact same stale "mcp" literal and were silently broken until
--   fixed in this same pass — ExactTextResponseTypeResolver.fallbackText(),
--   SemanticInterpretService.resolveClarificationSelection(), and
--   SchemaExtractionStep.clearStaleMcpContextIfIdentifiersUpdated(). If the
--   framework's own code had this bug, prod data seeded against the old key
--   almost certainly does too.
--
-- Safe to re-run: REPLACE()-based, idempotent, only touches rows containing
-- the literal substring "context.mcp" (covers "context.mcp.foo",
-- "@.context.mcp.foo" in JsonPath, and "${context.mcp...}" /
-- "context.mcp != null" in Thymeleaf — all share the same substring).
-- ============================================================================

UPDATE ce_rule
SET    match_pattern = REPLACE(match_pattern, 'context.mcp', 'context.agent')
WHERE  match_pattern LIKE '%context.mcp%';

UPDATE ce_response
SET    exact_text = REPLACE(exact_text, 'context.mcp', 'context.agent')
WHERE  exact_text LIKE '%context.mcp%';

UPDATE ce_response
SET    derivation_hint = REPLACE(derivation_hint, 'context.mcp', 'context.agent')
WHERE  derivation_hint LIKE '%context.mcp%';

UPDATE ce_prompt_template
SET    system_prompt = REPLACE(system_prompt, 'context.mcp', 'context.agent')
WHERE  system_prompt LIKE '%context.mcp%';

UPDATE ce_prompt_template
SET    user_prompt = REPLACE(user_prompt, 'context.mcp', 'context.agent')
WHERE  user_prompt LIKE '%context.mcp%';

UPDATE ce_agent_planner
SET    system_prompt = REPLACE(system_prompt, 'context.mcp', 'context.agent')
WHERE  system_prompt LIKE '%context.mcp%';

UPDATE ce_agent_planner
SET    user_prompt = REPLACE(user_prompt, 'context.mcp', 'context.agent')
WHERE  user_prompt LIKE '%context.mcp%';

COMMIT;

-- ============================================================================
-- VERIFICATION (all should return 0 rows / empty after this script)
-- ============================================================================
-- SELECT step_match, step_value, COUNT(*) FROM ce_verbose
--   WHERE step_match = 'McpToolStep' OR step_value LIKE 'MCP_%'
--   GROUP BY step_match, step_value;
--
-- SELECT config_key FROM ce_config WHERE config_key LIKE 'MCP_TOOL_%';
--
-- SELECT phase, COUNT(*) FROM ce_rule
--   WHERE phase IN ('POST_AGENT_MCP','AGENT_POST_MCP','PRE_AGENT_MCP')
--   GROUP BY phase;
--
-- SELECT table_name FROM information_schema.tables
--   WHERE table_name IN (
--     'ce_mcp_db_tool','ce_mcp_user_query_knowledge','ce_mcp_user_feedback',
--     'ce_mcp_planner','ce_mcp_tool',
--     'ce_semantic_model','ce_semantic_setting','ce_semantic_source_column',
--     'ce_semantic_source_table','ce_semantic_lexicon',
--     'ce_semantic_rule_allowed_table','ce_semantic_rule_deny_operation',
--     'ce_semantic_rule_config','ce_semantic_intent_rule','ce_semantic_metric',
--     'ce_semantic_entity_override','ce_semantic_relationship_override'
--   );  -- must be empty: nothing legacy/renamed should remain
--
-- SELECT table_name FROM information_schema.tables
--   WHERE table_name IN (
--     'ce_agent_tool','ce_agent_db_tool','ce_agent_planner',
--     'ce_agent_query_knowledge','ce_agent_user_feedback','ce_mcp_server',
--     'ce_semantic_concept','ce_semantic_synonym','ce_semantic_concept_embedding',
--     'ce_semantic_mapping','ce_semantic_join_path','ce_semantic_query_class',
--     'ce_semantic_ambiguity_option','ce_semantic_query_failures'
--   );  -- must return all 14: everything current should be present
--
-- SELECT rule_id, phase, intent_code, state_code FROM ce_rule
--   WHERE match_pattern LIKE '%context.mcp%';
-- SELECT response_id, intent_code, state_code FROM ce_response
--   WHERE exact_text LIKE '%context.mcp%' OR derivation_hint LIKE '%context.mcp%';
-- SELECT template_id, intent_code, state_code FROM ce_prompt_template
--   WHERE system_prompt LIKE '%context.mcp%' OR user_prompt LIKE '%context.mcp%';
-- SELECT planner_id, intent_code, state_code FROM ce_agent_planner
--   WHERE system_prompt LIKE '%context.mcp%' OR user_prompt LIKE '%context.mcp%';
