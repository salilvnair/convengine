-- PostgreSQL standalone script for verbose messaging table.
-- Run this if you only want to apply verbose table changes.

DROP TABLE IF EXISTS ce_verbose CASCADE;

CREATE TABLE ce_verbose (
    verbose_id bigserial NOT NULL,
    intent_code text NOT NULL,
    state_code text NOT NULL,
    step_match text DEFAULT 'EXACT' NOT NULL,
    step_value text NOT NULL,
    determinant text NOT NULL,
    rule_id int8 NULL,
    tool_code text NULL,
    message text NULL,
    error_message text NULL,
    priority int4 DEFAULT 100 NOT NULL,
    enabled bool DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT ce_verbose_pkey PRIMARY KEY (verbose_id),
    CONSTRAINT ce_verbose_intent_not_blank CHECK (btrim(intent_code) <> ''),
    CONSTRAINT ce_verbose_state_not_blank CHECK (btrim(state_code) <> ''),
    CONSTRAINT ce_verbose_step_match_not_blank CHECK (btrim(step_match) <> ''),
    CONSTRAINT ce_verbose_step_value_not_blank CHECK (btrim(step_value) <> ''),
    CONSTRAINT ce_verbose_determinant_not_blank CHECK (btrim(determinant) <> '')
);

CREATE INDEX idx_ce_verbose_lookup
ON public.ce_verbose USING btree
    (enabled, intent_code, state_code, determinant, step_match, step_value, rule_id, tool_code, priority);
