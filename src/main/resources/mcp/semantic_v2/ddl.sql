-- Semantic V2 dynamic overlay tables.
-- These tables hold mutable semantic assets that should not live in semantic-layer.yaml.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS ce_semantic_entity_override (
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
CREATE INDEX IF NOT EXISTS idx_ce_semantic_entity_override_lookup
    ON public.ce_semantic_entity_override USING btree (enabled, entity_name, priority);

CREATE TABLE IF NOT EXISTS ce_semantic_relationship_override (
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
CREATE INDEX IF NOT EXISTS idx_ce_semantic_relationship_override_lookup
    ON public.ce_semantic_relationship_override USING btree (enabled, relationship_name, priority);

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
