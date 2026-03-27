-- Semantic V2 migration DDL for table renames.
-- Run once before applying semantic_v2_ddl.sql / semantic_v2_dml.sql.

ALTER TABLE IF EXISTS ce_semantic_entity_override
    RENAME TO ce_semantic_entity;

ALTER TABLE IF EXISTS ce_semantic_relationship_override
    RENAME TO ce_semantic_relationship;

ALTER INDEX IF EXISTS idx_ce_semantic_entity_override_lookup
    RENAME TO idx_ce_semantic_entity_lookup;

ALTER INDEX IF EXISTS idx_ce_semantic_relationship_override_lookup
    RENAME TO idx_ce_semantic_relationship_lookup;

-- Backfill columns for older semantic_v2 deployments.
ALTER TABLE IF EXISTS ce_semantic_ambiguity_option
    ADD COLUMN IF NOT EXISTS ambiguity_code VARCHAR(120);

ALTER TABLE IF EXISTS ce_semantic_ambiguity_option
    ADD COLUMN IF NOT EXISTS field_key VARCHAR(120);

ALTER TABLE IF EXISTS ce_semantic_ambiguity_option
    ADD COLUMN IF NOT EXISTS recommended BOOLEAN DEFAULT false NOT NULL;
