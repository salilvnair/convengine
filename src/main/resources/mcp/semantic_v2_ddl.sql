-- Semantic V2 dynamic semantic tables.
-- These tables are the source of truth for semantic metadata.
CREATE EXTENSION IF NOT EXISTS vector;

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

-- -------------------------------------------------------------------
-- Canonical semantic model assets (interpret/resolve pipeline)
-- -------------------------------------------------------------------

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

-- Optional but recommended
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
