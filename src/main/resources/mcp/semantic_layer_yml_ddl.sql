-- YAML-only semantic schema (derived from former semantic-layer.yml sections).
-- This intentionally excludes Agent-2 query-generation tables.

CREATE TABLE IF NOT EXISTS ce_semantic_model (
    id BIGSERIAL PRIMARY KEY,
    model_version INTEGER NOT NULL,
    database_name VARCHAR(255) NOT NULL,
    description TEXT,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ce_semantic_model_db_version
    ON public.ce_semantic_model USING btree (database_name, model_version);

CREATE TABLE IF NOT EXISTS ce_semantic_setting (
    id BIGSERIAL PRIMARY KEY,
    setting_key VARCHAR(255) NOT NULL,
    setting_value VARCHAR(4000) NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ce_semantic_setting_key
    ON public.ce_semantic_setting USING btree (setting_key);

CREATE TABLE IF NOT EXISTS ce_semantic_source_table (
    id BIGSERIAL PRIMARY KEY,
    table_name VARCHAR(255) NOT NULL,
    description TEXT,
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ce_semantic_source_table_name
    ON public.ce_semantic_source_table USING btree (table_name);

CREATE TABLE IF NOT EXISTS ce_semantic_source_column (
    id BIGSERIAL PRIMARY KEY,
    table_name VARCHAR(255) NOT NULL,
    column_name VARCHAR(255) NOT NULL,
    data_type VARCHAR(100),
    is_primary_key BOOLEAN DEFAULT false NOT NULL,
    description TEXT,
    foreign_key_table VARCHAR(255),
    foreign_key_column VARCHAR(255),
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ce_semantic_source_column
    ON public.ce_semantic_source_column USING btree (table_name, column_name);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_source_column_fk
    ON public.ce_semantic_source_column USING btree (foreign_key_table, foreign_key_column);

CREATE TABLE IF NOT EXISTS ce_semantic_entity (
    id BIGSERIAL PRIMARY KEY,
    entity_name VARCHAR(255) NOT NULL,
    description TEXT,
    primary_table VARCHAR(255),
    related_tables VARCHAR(2000),
    synonyms VARCHAR(2000),
    fields_json jsonb,
    priority INTEGER DEFAULT 100 NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_entity_lookup
    ON public.ce_semantic_entity USING btree (enabled, entity_name, priority);

CREATE TABLE IF NOT EXISTS ce_semantic_relationship (
    id BIGSERIAL PRIMARY KEY,
    relationship_name VARCHAR(255) NOT NULL,
    description TEXT,
    from_table VARCHAR(255) NOT NULL,
    from_column VARCHAR(255) NOT NULL,
    to_table VARCHAR(255) NOT NULL,
    to_column VARCHAR(255) NOT NULL,
    relation_type VARCHAR(100),
    priority INTEGER DEFAULT 100 NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_relationship_lookup
    ON public.ce_semantic_relationship USING btree (enabled, relationship_name, priority);

CREATE TABLE IF NOT EXISTS ce_semantic_lexicon (
    id BIGSERIAL PRIMARY KEY,
    term_key VARCHAR(255) NOT NULL,
    synonym_text VARCHAR(500) NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ce_semantic_lexicon_term_synonym
    ON public.ce_semantic_lexicon USING btree (term_key, synonym_text);

CREATE TABLE IF NOT EXISTS ce_semantic_join_hint (
    id BIGSERIAL PRIMARY KEY,
    base_table VARCHAR(255) NOT NULL,
    join_table VARCHAR(255) NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_join_hint_lookup
    ON public.ce_semantic_join_hint USING btree (enabled, base_table, priority, join_table);

CREATE TABLE IF NOT EXISTS ce_semantic_value_pattern (
    id BIGSERIAL PRIMARY KEY,
    from_field VARCHAR(255) NOT NULL,
    to_field VARCHAR(255) NOT NULL,
    value_starts_with VARCHAR(2000) NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ce_semantic_value_pattern_lookup
    ON public.ce_semantic_value_pattern USING btree (enabled, from_field, to_field, priority);

CREATE TABLE IF NOT EXISTS ce_semantic_metric (
    id BIGSERIAL PRIMARY KEY,
    metric_key VARCHAR(255) NOT NULL,
    metric_expr TEXT,
    description TEXT,
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ce_semantic_metric_key
    ON public.ce_semantic_metric USING btree (metric_key);

CREATE TABLE IF NOT EXISTS ce_semantic_intent_rule (
    id BIGSERIAL PRIMARY KEY,
    rule_key VARCHAR(255) NOT NULL,
    rule_json jsonb,
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ce_semantic_intent_rule_key
    ON public.ce_semantic_intent_rule USING btree (rule_key);

CREATE TABLE IF NOT EXISTS ce_semantic_rule_allowed_table (
    id BIGSERIAL PRIMARY KEY,
    table_name VARCHAR(255) NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ce_semantic_rule_allowed_table
    ON public.ce_semantic_rule_allowed_table USING btree (table_name);

CREATE TABLE IF NOT EXISTS ce_semantic_rule_deny_operation (
    id BIGSERIAL PRIMARY KEY,
    operation_name VARCHAR(50) NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    priority INTEGER DEFAULT 100 NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ce_semantic_rule_deny_operation
    ON public.ce_semantic_rule_deny_operation USING btree (operation_name);

CREATE TABLE IF NOT EXISTS ce_semantic_rule_config (
    id BIGSERIAL PRIMARY KEY,
    max_result_limit INTEGER,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);
