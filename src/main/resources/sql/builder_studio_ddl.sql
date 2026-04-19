-- ============================================================================
-- Builder Studio DDL — PostgreSQL
-- Persists workspaces, teams, agent pools, agents, skills, and workflows
-- that the front-end builder studio manages in localStorage / Zustand.
-- ============================================================================

-- Drop in reverse-dependency order
DROP TABLE IF EXISTS ce_bs_workflow        CASCADE;
DROP TABLE IF EXISTS ce_bs_agent_skill     CASCADE;
DROP TABLE IF EXISTS ce_bs_agent           CASCADE;
DROP TABLE IF EXISTS ce_bs_skill           CASCADE;
DROP TABLE IF EXISTS ce_bs_agent_pool      CASCADE;
DROP TABLE IF EXISTS ce_bs_team            CASCADE;
DROP TABLE IF EXISTS ce_bs_workspace       CASCADE;

-- ─── Workspace ──────────────────────────────────────────────────────────────
-- Top-level container. Each user/tenant can have multiple workspaces.
CREATE TABLE ce_bs_workspace (
    workspace_id    text            NOT NULL,
    name            text            NOT NULL,
    description     text,
    created_at      timestamptz     DEFAULT now() NOT NULL,
    updated_at      timestamptz     DEFAULT now() NOT NULL,
    CONSTRAINT ce_bs_workspace_pkey PRIMARY KEY (workspace_id)
);

-- ─── Team ───────────────────────────────────────────────────────────────────
CREATE TABLE ce_bs_team (
    team_id         text            NOT NULL,
    workspace_id    text            NOT NULL,
    name            text            NOT NULL,
    description     text,
    created_at      timestamptz     DEFAULT now() NOT NULL,
    updated_at      timestamptz     DEFAULT now() NOT NULL,
    CONSTRAINT ce_bs_team_pkey PRIMARY KEY (team_id),
    CONSTRAINT ce_bs_team_workspace_fk FOREIGN KEY (workspace_id)
        REFERENCES ce_bs_workspace (workspace_id) ON DELETE CASCADE
);
CREATE INDEX idx_ce_bs_team_workspace ON ce_bs_team (workspace_id);

-- ─── Agent Pool ─────────────────────────────────────────────────────────────
-- Groups agents within a team (maps to front-end agentPools).
CREATE TABLE ce_bs_agent_pool (
    pool_id         text            NOT NULL,
    team_id         text            NOT NULL,
    name            text            NOT NULL,
    description     text,
    created_at      timestamptz     DEFAULT now() NOT NULL,
    updated_at      timestamptz     DEFAULT now() NOT NULL,
    CONSTRAINT ce_bs_agent_pool_pkey PRIMARY KEY (pool_id),
    CONSTRAINT ce_bs_agent_pool_team_fk FOREIGN KEY (team_id)
        REFERENCES ce_bs_team (team_id) ON DELETE CASCADE
);
CREATE INDEX idx_ce_bs_agent_pool_team ON ce_bs_agent_pool (team_id);

-- ─── Skill ──────────────────────────────────────────────────────────────────
CREATE TABLE ce_bs_skill (
    skill_id        text            NOT NULL,
    workspace_id    text            NOT NULL,
    name            text            NOT NULL,
    language        text            DEFAULT 'javascript',
    source          text,                               -- skill source code
    input_schema    jsonb           DEFAULT '{}'::jsonb,
    output_schema   jsonb           DEFAULT '{}'::jsonb,
    created_at      timestamptz     DEFAULT now() NOT NULL,
    updated_at      timestamptz     DEFAULT now() NOT NULL,
    CONSTRAINT ce_bs_skill_pkey PRIMARY KEY (skill_id),
    CONSTRAINT ce_bs_skill_workspace_fk FOREIGN KEY (workspace_id)
        REFERENCES ce_bs_workspace (workspace_id) ON DELETE CASCADE
);
CREATE INDEX idx_ce_bs_skill_workspace ON ce_bs_skill (workspace_id);

-- ─── Agent ──────────────────────────────────────────────────────────────────
CREATE TABLE ce_bs_agent (
    agent_id        text            NOT NULL,
    pool_id         text            NOT NULL,
    name            text            NOT NULL,
    model           text,
    provider        text,
    system_prompt   text,
    user_prompt     text,
    input_schema    jsonb           DEFAULT '{}'::jsonb,
    output_schema   jsonb           DEFAULT '{}'::jsonb,
    strict_input    boolean         DEFAULT false,
    strict_output   boolean         DEFAULT false,
    created_at      timestamptz     DEFAULT now() NOT NULL,
    updated_at      timestamptz     DEFAULT now() NOT NULL,
    CONSTRAINT ce_bs_agent_pkey PRIMARY KEY (agent_id),
    CONSTRAINT ce_bs_agent_pool_fk FOREIGN KEY (pool_id)
        REFERENCES ce_bs_agent_pool (pool_id) ON DELETE CASCADE
);
CREATE INDEX idx_ce_bs_agent_pool ON ce_bs_agent (pool_id);

-- ─── Agent ↔ Skill (many-to-many) ──────────────────────────────────────────
CREATE TABLE ce_bs_agent_skill (
    agent_id        text            NOT NULL,
    skill_id        text            NOT NULL,
    CONSTRAINT ce_bs_agent_skill_pkey PRIMARY KEY (agent_id, skill_id),
    CONSTRAINT ce_bs_agent_skill_agent_fk FOREIGN KEY (agent_id)
        REFERENCES ce_bs_agent (agent_id) ON DELETE CASCADE,
    CONSTRAINT ce_bs_agent_skill_skill_fk FOREIGN KEY (skill_id)
        REFERENCES ce_bs_skill (skill_id) ON DELETE CASCADE
);

-- ─── Workflow ───────────────────────────────────────────────────────────────
-- The canvas graph: nodes, edges, and per-node configuration live as JSONB.
CREATE TABLE ce_bs_workflow (
    workflow_id         text            NOT NULL,
    workspace_id        text            NOT NULL,
    team_id             text,
    name                text            NOT NULL,
    description         text,
    nodes               jsonb           DEFAULT '[]'::jsonb  NOT NULL,
    edges               jsonb           DEFAULT '[]'::jsonb  NOT NULL,
    sub_block_values    jsonb           DEFAULT '{}'::jsonb   NOT NULL,
    metadata            jsonb           DEFAULT '{}'::jsonb,  -- timeout, retries, logLevel, tags
    created_at          timestamptz     DEFAULT now() NOT NULL,
    updated_at          timestamptz     DEFAULT now() NOT NULL,
    CONSTRAINT ce_bs_workflow_pkey PRIMARY KEY (workflow_id),
    CONSTRAINT ce_bs_workflow_workspace_fk FOREIGN KEY (workspace_id)
        REFERENCES ce_bs_workspace (workspace_id) ON DELETE CASCADE,
    CONSTRAINT ce_bs_workflow_team_fk FOREIGN KEY (team_id)
        REFERENCES ce_bs_team (team_id) ON DELETE SET NULL
);
CREATE INDEX idx_ce_bs_workflow_workspace ON ce_bs_workflow (workspace_id);
CREATE INDEX idx_ce_bs_workflow_team      ON ce_bs_workflow (team_id);
