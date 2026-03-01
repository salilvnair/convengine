DROP TABLE IF EXISTS ce_mcp_db_tool CASCADE;
DROP TABLE IF EXISTS ce_mcp_planner CASCADE;
DROP TABLE IF EXISTS ce_conversation_history CASCADE;
DROP TABLE IF EXISTS ce_audit CASCADE;
DROP TABLE IF EXISTS ce_pending_action CASCADE;
DROP TABLE IF EXISTS ce_verbose CASCADE;
DROP TABLE IF EXISTS ce_rule CASCADE;
DROP TABLE IF EXISTS ce_response CASCADE;
DROP TABLE IF EXISTS ce_prompt_template CASCADE;
DROP TABLE IF EXISTS ce_policy CASCADE;
DROP TABLE IF EXISTS ce_output_schema CASCADE;
DROP TABLE IF EXISTS ce_mcp_tool CASCADE;
DROP TABLE IF EXISTS ce_llm_call_log CASCADE;
DROP TABLE IF EXISTS ce_intent_classifier CASCADE;
DROP TABLE IF EXISTS ce_intent CASCADE;
DROP TABLE IF EXISTS ce_conversation CASCADE;
DROP TABLE IF EXISTS ce_container_config CASCADE;
DROP TABLE IF EXISTS ce_config CASCADE;

CREATE TABLE ce_config (
                           config_id int4 NOT NULL,
                           config_type text NOT NULL,
                           config_key text NOT NULL,
                           config_value text NOT NULL,
                           enabled bool DEFAULT true NOT NULL,
                           created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
                           CONSTRAINT ce_config_pkey PRIMARY KEY (config_id)
);
CREATE UNIQUE INDEX ux_ce_config_type_key ON public.ce_config USING btree (config_type, config_key);

CREATE TABLE ce_container_config (
                                     id bigserial NOT NULL,
                                     intent_code text NOT NULL,
                                     state_code text NOT NULL,
                                     page_id int4 NOT NULL,
                                     section_id int4 NOT NULL,
                                     container_id int4 NOT NULL,
                                     input_param_name text NOT NULL,
                                     priority int4 DEFAULT 1 NOT NULL,
                                     enabled bool DEFAULT true NOT NULL,
                                     created_at timestamptz DEFAULT now() NOT NULL,
                                     CONSTRAINT ce_validation_config_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_ce_validation_config_lookup ON public.ce_container_config USING btree (intent_code, state_code, enabled, priority);

CREATE TABLE ce_conversation (
                                 conversation_id uuid DEFAULT uuid_generate_v4() NOT NULL,
                                 status text NOT NULL,
                                 intent_code text DEFAULT 'UNKNOWN'::text NOT NULL,
                                 state_code text DEFAULT 'UNKNOWN'::text NOT NULL,
                                 context_json jsonb DEFAULT '{}'::jsonb NOT NULL,
                                 last_user_text text NULL,
                                 last_assistant_json jsonb NULL,
                                 input_params_json jsonb DEFAULT '{}'::jsonb NOT NULL,
                                 created_at timestamptz DEFAULT now() NOT NULL,
                                 updated_at timestamptz DEFAULT now() NOT NULL,
                                 CONSTRAINT ce_conversation_pkey PRIMARY KEY (conversation_id),
                                 CONSTRAINT ce_conversation_intent_not_blank CHECK (btrim(intent_code) <> ''),
                                 CONSTRAINT ce_conversation_state_not_blank CHECK (btrim(state_code) <> '')
);
ALTER TABLE ce_conversation DROP CONSTRAINT IF EXISTS idx_ce_conversation_status;
ALTER TABLE ce_conversation DROP CONSTRAINT IF EXISTS idx_ce_conversation_updated;
DROP INDEX IF EXISTS idx_ce_conversation_status;
DROP INDEX IF EXISTS idx_ce_conversation_updated;
CREATE INDEX idx_ce_conversation_status ON public.ce_conversation USING btree (status);
CREATE INDEX idx_ce_conversation_updated ON public.ce_conversation USING btree (updated_at);

CREATE TABLE ce_intent (
                           intent_code text NOT NULL,
                           description text NOT NULL,
                           priority int4 DEFAULT 100 NOT NULL,
                           enabled bool DEFAULT true NOT NULL,
                           created_at timestamptz DEFAULT now() NOT NULL,
                           display_name text NULL,
                           llm_hint text NULL,
                           CONSTRAINT ce_intent_pkey PRIMARY KEY (intent_code)
);
CREATE INDEX ix_ce_intent_enabled_priority ON public.ce_intent USING btree (enabled, priority, intent_code);

CREATE TABLE ce_intent_classifier (
                                      classifier_id bigserial NOT NULL,
                                      intent_code text NOT NULL,
                                      state_code text DEFAULT 'UNKNOWN'::text NOT NULL,
                                      rule_type text NOT NULL,
                                      pattern text NOT NULL,
                                      priority int4 NOT NULL,
                                      enabled bool DEFAULT true NULL,
                                      description text NULL,
                                      CONSTRAINT ce_intent_classifier_pkey PRIMARY KEY (classifier_id),
                                      CONSTRAINT ce_intent_classifier_state_not_blank CHECK (btrim(state_code) <> '')
);

CREATE TABLE ce_llm_call_log (
                                 llm_call_id bigserial NOT NULL,
                                 conversation_id uuid NOT NULL,
                                 intent_code text DEFAULT 'UNKNOWN'::text NOT NULL,
                                 state_code text DEFAULT 'UNKNOWN'::text NOT NULL,
                                 provider text NOT NULL,
                                 model text NOT NULL,
                                 temperature numeric(3, 2) NULL,
                                 prompt_text text NOT NULL,
                                 user_context text NOT NULL,
                                 response_text text NULL,
                                 success bool NOT NULL,
                                 error_message text NULL,
                                 created_at timestamptz DEFAULT now() NOT NULL,
                                 CONSTRAINT ce_llm_call_log_pkey PRIMARY KEY (llm_call_id),
                                 CONSTRAINT ce_llm_call_log_intent_not_blank CHECK (btrim(intent_code) <> ''),
                                 CONSTRAINT ce_llm_call_log_state_not_blank CHECK (btrim(state_code) <> '')
);
CREATE INDEX idx_ce_llm_log_conversation ON public.ce_llm_call_log USING btree (conversation_id);
CREATE INDEX idx_ce_llm_log_intent_state ON public.ce_llm_call_log USING btree (intent_code, state_code);

CREATE TABLE ce_mcp_tool (
                             tool_id bigserial NOT NULL,
                             tool_code text NOT NULL,
                             tool_group text NOT NULL,
                             intent_code text NOT NULL,
                             state_code text NOT NULL,
                             enabled bool DEFAULT true NOT NULL,
                             description text NULL,
                             created_at timestamptz DEFAULT now() NOT NULL,
                             CONSTRAINT ce_mcp_tool_pkey PRIMARY KEY (tool_id),
                             CONSTRAINT ce_mcp_tool_tool_code_key UNIQUE (tool_code),
                             CONSTRAINT ce_mcp_tool_intent_code_not_blank CHECK (btrim(intent_code) <> ''),
                             CONSTRAINT ce_mcp_tool_state_code_not_blank CHECK (btrim(state_code) <> '')
);
CREATE INDEX idx_ce_mcp_tool_enabled ON public.ce_mcp_tool USING btree (enabled, intent_code, state_code, tool_group, tool_code);

CREATE TABLE ce_output_schema (
                                  schema_id bigserial NOT NULL,
                                  intent_code text NOT NULL,
                                  state_code text NOT NULL,
                                  json_schema jsonb NOT NULL,
                                  description text NULL,
                                  enabled bool DEFAULT true NULL,
                                  priority int4 NOT NULL,
                                  CONSTRAINT ce_output_schema_pkey PRIMARY KEY (schema_id)
);
CREATE INDEX idx_ce_output_schema_lookup ON public.ce_output_schema USING btree (intent_code, state_code, enabled, priority);

CREATE TABLE ce_policy (
                           policy_id bigserial NOT NULL,
                           rule_type text NOT NULL,
                           pattern text NOT NULL,
                           response_text text NOT NULL,
                           priority int4 DEFAULT 10 NOT NULL,
                           enabled bool DEFAULT true NOT NULL,
                           description text NULL,
                           created_at timestamptz DEFAULT now() NOT NULL,
                           CONSTRAINT ce_policy_pkey PRIMARY KEY (policy_id)
);
CREATE INDEX idx_ce_policy_priority ON public.ce_policy USING btree (enabled, priority);

CREATE TABLE ce_prompt_template (
                                    template_id bigserial NOT NULL,
                                    intent_code text NOT NULL,
                                    state_code text NOT NULL,
                                    response_type text NOT NULL,
                                    system_prompt text NOT NULL,
                                    user_prompt text NOT NULL,
                                    temperature numeric(3, 2) DEFAULT 0.0 NOT NULL,
                                    interaction_mode text,
                                    interaction_contract text,
                                    enabled bool DEFAULT true NOT NULL,
                                    created_at timestamptz DEFAULT now() NOT NULL,
                                    CONSTRAINT ce_prompt_template_pkey PRIMARY KEY (template_id),
                                    CONSTRAINT ce_prompt_template_intent_not_blank CHECK (btrim(intent_code) <> ''),
                                    CONSTRAINT ce_prompt_template_state_not_blank CHECK (btrim(state_code) <> '')
);
CREATE INDEX idx_ce_prompt_template_lookup ON public.ce_prompt_template USING btree (response_type, intent_code, state_code, enabled);

CREATE TABLE ce_response (
                             response_id bigserial NOT NULL,
                             intent_code text NOT NULL,
                             state_code text NOT NULL,
                             output_format text NOT NULL,
                             response_type text NOT NULL,
                             exact_text text NULL,
                             derivation_hint text NULL,
                             json_schema jsonb NULL,
                             priority int4 DEFAULT 100 NOT NULL,
                             enabled bool DEFAULT true NOT NULL,
                             description text NULL,
                             created_at timestamptz DEFAULT now() NOT NULL,
                             CONSTRAINT ce_response_pkey PRIMARY KEY (response_id),
                             CONSTRAINT ce_response_intent_not_blank CHECK (btrim(intent_code) <> ''),
                             CONSTRAINT ce_response_state_not_blank CHECK (btrim(state_code) <> '')
);
CREATE INDEX idx_ce_response_intent_state ON public.ce_response USING btree (intent_code, state_code, enabled, priority);
CREATE INDEX idx_ce_response_lookup ON public.ce_response USING btree (state_code, enabled, priority);

CREATE TABLE ce_rule (
                         rule_id bigserial NOT NULL,
                         phase text DEFAULT 'PRE_RESPONSE_RESOLUTION' NOT NULL,
                         intent_code text NOT NULL,
                         state_code text NOT NULL,
                         rule_type text NOT NULL,
                         match_pattern text NOT NULL,
                         "action" text NOT NULL,
                         action_value text NULL,
                         priority int4 DEFAULT 100 NOT NULL,
                         enabled bool DEFAULT true NOT NULL,
                         description text NULL,
                         created_at timestamptz DEFAULT now() NOT NULL,
                         CONSTRAINT ce_rule_pkey PRIMARY KEY (rule_id),
                         CONSTRAINT ce_rule_intent_not_blank CHECK (btrim(intent_code) <> ''),
                         CONSTRAINT ce_rule_state_not_blank CHECK (btrim(state_code) <> '')
);
CREATE INDEX idx_ce_rule_priority ON public.ce_rule USING btree (enabled, phase, state_code, priority);

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
CREATE INDEX idx_ce_verbose_lookup ON public.ce_verbose USING btree (enabled, intent_code, state_code, determinant, step_match, step_value, rule_id, tool_code, priority);

CREATE TABLE ce_pending_action (
                                  pending_action_id bigserial NOT NULL,
                                  intent_code text NOT NULL,
                                  state_code text NOT NULL,
                                  action_key text NOT NULL,
                                  bean_name text NOT NULL,
                                  method_names text NOT NULL,
                                  priority int4 DEFAULT 100 NOT NULL,
                                  enabled bool DEFAULT true NOT NULL,
                                  description text NULL,
                                  created_at timestamptz DEFAULT now() NOT NULL,
                                  CONSTRAINT ce_pending_action_pkey PRIMARY KEY (pending_action_id),
                                  CONSTRAINT ce_pending_action_intent_not_blank CHECK (btrim(intent_code) <> ''),
                                  CONSTRAINT ce_pending_action_state_not_blank CHECK (btrim(state_code) <> '')
);
CREATE INDEX idx_ce_pending_action_lookup ON public.ce_pending_action USING btree (enabled, action_key, intent_code, state_code, priority);


CREATE TABLE ce_audit (
                          audit_id bigserial NOT NULL,
                          conversation_id uuid NOT NULL,
                          stage text NOT NULL,
                          payload_json jsonb NOT NULL,
                          created_at timestamptz DEFAULT now() NOT NULL,
                          CONSTRAINT ce_audit_pkey PRIMARY KEY (audit_id),
                          CONSTRAINT ce_audit_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES ce_conversation(conversation_id) ON DELETE CASCADE
);
CREATE INDEX idx_ce_audit_conversation ON public.ce_audit USING btree (conversation_id, created_at DESC);

CREATE TABLE ce_conversation_history (
                          history_id bigserial NOT NULL,
                          conversation_id uuid NOT NULL,
                          user_input text NOT NULL,
                          assistant_output jsonb NULL,
                          created_at timestamptz DEFAULT now() NOT NULL,
                          modified_at timestamptz DEFAULT now() NOT NULL,
                          CONSTRAINT ce_conversation_history_pkey PRIMARY KEY (history_id),
                          CONSTRAINT ce_conversation_history_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES ce_conversation(conversation_id) ON DELETE CASCADE
);
CREATE INDEX idx_ce_conversation_history_conv ON public.ce_conversation_history USING btree (conversation_id, created_at DESC);

CREATE TABLE ce_mcp_db_tool (
                                tool_id int8 NOT NULL,
                                dialect text DEFAULT 'POSTGRES'::text NOT NULL,
                                sql_template text NOT NULL,
                                param_schema jsonb NOT NULL,
                                safe_mode bool DEFAULT true NOT NULL,
                                max_rows int4 DEFAULT 200 NOT NULL,
                                created_at timestamptz DEFAULT now() NOT NULL,
                                allowed_identifiers jsonb NULL,
                                CONSTRAINT ce_mcp_db_tool_pkey PRIMARY KEY (tool_id),
                                CONSTRAINT ce_mcp_db_tool_tool_id_fkey FOREIGN KEY (tool_id) REFERENCES ce_mcp_tool(tool_id) ON DELETE CASCADE
);
CREATE INDEX idx_ce_mcp_db_tool_dialect ON public.ce_mcp_db_tool USING btree (dialect);

CREATE TABLE ce_mcp_planner (
                                planner_id bigserial NOT NULL,
                                intent_code text NOT NULL,
                                state_code text NOT NULL,
                                system_prompt text NOT NULL,
                                user_prompt text NOT NULL,
                                enabled bool DEFAULT true NOT NULL,
                                created_at timestamptz DEFAULT now() NOT NULL,
                                CONSTRAINT ce_mcp_planner_pkey PRIMARY KEY (planner_id),
                                CONSTRAINT ce_mcp_planner_intent_not_blank CHECK (btrim(intent_code) <> ''),
                                CONSTRAINT ce_mcp_planner_state_not_blank CHECK (btrim(state_code) <> '')
);
CREATE INDEX idx_ce_mcp_planner_scope ON public.ce_mcp_planner USING btree (enabled, intent_code, state_code, planner_id);
