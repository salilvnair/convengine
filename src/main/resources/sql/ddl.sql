-- public.ce_config definition

-- Drop table

-- DROP TABLE ce_config;

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


-- public.ce_container_config definition

-- Drop table

-- DROP TABLE ce_container_config;

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


-- public.ce_conversation definition

-- Drop table

-- DROP TABLE ce_conversation;

CREATE TABLE ce_conversation (
                                 conversation_id uuid DEFAULT uuid_generate_v4() NOT NULL,
                                 status text NOT NULL,
                                 intent_code text NULL,
                                 state_code text NOT NULL,
                                 context_json jsonb DEFAULT '{}'::jsonb NOT NULL,
                                 last_user_text text NULL,
                                 last_assistant_json jsonb NULL,
                                 input_params_json jsonb DEFAULT '{}'::jsonb NOT NULL,
                                 created_at timestamptz DEFAULT now() NOT NULL,
                                 updated_at timestamptz DEFAULT now() NOT NULL,
                                 CONSTRAINT ce_conversation_pkey PRIMARY KEY (conversation_id)
);
CREATE INDEX idx_ce_conversation_status ON public.ce_conversation USING btree (status);
CREATE INDEX idx_ce_conversation_updated ON public.ce_conversation USING btree (updated_at);


-- public.ce_intent definition

-- Drop table

-- DROP TABLE ce_intent;

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


-- public.ce_intent_classifier definition

-- Drop table

-- DROP TABLE ce_intent_classifier;

CREATE TABLE ce_intent_classifier (
                                      classifier_id bigserial NOT NULL,
                                      intent_code text NOT NULL,
                                      rule_type text NOT NULL,
                                      pattern text NOT NULL,
                                      priority int4 NOT NULL,
                                      enabled bool DEFAULT true NULL,
                                      description text NULL,
                                      CONSTRAINT ce_intent_classifier_pkey PRIMARY KEY (classifier_id)
);


-- public.ce_llm_call_log definition

-- Drop table

-- DROP TABLE ce_llm_call_log;

CREATE TABLE ce_llm_call_log (
                                 llm_call_id bigserial NOT NULL,
                                 conversation_id uuid NOT NULL,
                                 intent_code text NULL,
                                 state_code text NULL,
                                 provider text NOT NULL,
                                 model text NOT NULL,
                                 temperature numeric(3, 2) NULL,
                                 prompt_text text NOT NULL,
                                 user_context text NOT NULL,
                                 response_text text NULL,
                                 success bool NOT NULL,
                                 error_message text NULL,
                                 created_at timestamptz DEFAULT now() NOT NULL,
                                 CONSTRAINT ce_llm_call_log_pkey PRIMARY KEY (llm_call_id)
);
CREATE INDEX idx_ce_llm_log_conversation ON public.ce_llm_call_log USING btree (conversation_id);
CREATE INDEX idx_ce_llm_log_intent_state ON public.ce_llm_call_log USING btree (intent_code, state_code);


-- public.ce_mcp_tool definition

-- Drop table

-- DROP TABLE ce_mcp_tool;

CREATE TABLE ce_mcp_tool (
                             tool_id bigserial NOT NULL,
                             tool_code text NOT NULL,
                             tool_group text NOT NULL,
                             enabled bool DEFAULT true NOT NULL,
                             description text NULL,
                             created_at timestamptz DEFAULT now() NOT NULL,
                             CONSTRAINT ce_mcp_tool_pkey PRIMARY KEY (tool_id),
                             CONSTRAINT ce_mcp_tool_tool_code_key UNIQUE (tool_code)
);
CREATE INDEX idx_ce_mcp_tool_enabled ON public.ce_mcp_tool USING btree (enabled, tool_group, tool_code);


-- public.ce_output_schema definition

-- Drop table

-- DROP TABLE ce_output_schema;

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


-- public.ce_policy definition

-- Drop table

-- DROP TABLE ce_policy;

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


-- public.ce_prompt_template definition

-- Drop table

-- DROP TABLE ce_prompt_template;

CREATE TABLE ce_prompt_template (
                                    template_id bigserial NOT NULL,
                                    intent_code text NULL,
                                    state_code text NULL,
                                    response_type text NOT NULL,
                                    system_prompt text NOT NULL,
                                    user_prompt text NOT NULL,
                                    temperature numeric(3, 2) DEFAULT 0.0 NOT NULL,
                                    enabled bool DEFAULT true NOT NULL,
                                    created_at timestamptz DEFAULT now() NOT NULL,
                                    CONSTRAINT ce_prompt_template_pkey PRIMARY KEY (template_id)
);
CREATE INDEX idx_ce_prompt_template_lookup ON public.ce_prompt_template USING btree (response_type, intent_code, state_code, enabled);


-- public.ce_response definition

-- Drop table

-- DROP TABLE ce_response;

CREATE TABLE ce_response (
                             response_id bigserial NOT NULL,
                             intent_code text NULL,
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
                             CONSTRAINT ce_response_pkey PRIMARY KEY (response_id)
);
CREATE INDEX idx_ce_response_intent_state ON public.ce_response USING btree (intent_code, state_code, enabled, priority);
CREATE INDEX idx_ce_response_lookup ON public.ce_response USING btree (state_code, enabled, priority);


-- public.ce_rule definition

-- Drop table

-- DROP TABLE ce_rule;

CREATE TABLE ce_rule (
                         rule_id bigserial NOT NULL,
                         phase text DEFAULT 'PIPELINE_RULES' NOT NULL,
                         intent_code text NULL,
                         rule_type text NOT NULL,
                         match_pattern text NOT NULL,
                         "action" text NOT NULL,
                         action_value text NULL,
                         priority int4 DEFAULT 100 NOT NULL,
                         enabled bool DEFAULT true NOT NULL,
                         description text NULL,
                         created_at timestamptz DEFAULT now() NOT NULL,
                         CONSTRAINT ce_rule_pkey PRIMARY KEY (rule_id)
);
CREATE INDEX idx_ce_rule_priority ON public.ce_rule USING btree (enabled, phase, priority);


-- public.ce_validation_snapshot definition

-- Drop table

-- DROP TABLE ce_validation_snapshot;

CREATE TABLE ce_validation_snapshot (
                                        snapshot_id bigserial NOT NULL,
                                        conversation_id uuid NOT NULL,
                                        intent_code varchar(64) NULL,
                                        state_code varchar(64) NULL,
                                        validation_tables jsonb NULL,
                                        validation_decision text NULL,
                                        created_at timestamptz DEFAULT now() NOT NULL,
                                        schema_id int8 NULL,
                                        CONSTRAINT ce_validation_snapshot_pkey PRIMARY KEY (snapshot_id)
);
CREATE INDEX idx_ce_validation_snapshot_conv ON public.ce_validation_snapshot USING btree (conversation_id);


-- public.ce_audit definition

-- Drop table

-- DROP TABLE ce_audit;

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


-- public.ce_mcp_db_tool definition

-- Drop table

-- DROP TABLE ce_mcp_db_tool;

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

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(1, 'AgentIntentResolver', 'MIN_CONFIDENCE', '0.55', true, '2026-02-10 10:15:54.227');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(2, 'AgentIntentResolver', 'COLLISION_GAP_THRESHOLD', '0.2', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(3, 'AgentIntentResolver', 'SYSTEM_PROMPT', 'You are an intent resolution agent for a conversational engine.

                 Return JSON ONLY with fields:
                 {
                   "intent": "<INTENT_CODE_OR_NULL>",
                   "state": "INTENT_COLLISION | IDLE",
                   "confidence": 0.0,
                   "needsClarification": false,
                   "clarificationResolved": false,
                   "clarificationQuestion": "",
                   "intentScores": [{"intent":"<INTENT_CODE>","confidence":0.0}],
                   "followups": []
                 }

                 Rules:
                 - Score all plausible intents and return them in intentScores sorted by confidence descending.
                 - If top intents are close and ambiguous, set state to INTENT_COLLISION and needsClarification=true.
                 - For INTENT_COLLISION, add one follow-up disambiguation question in followups.
                 - If top intent is clear, set intent to best intent and confidence to best confidence.
                 - If user input is question-like (what/where/when/why/how/which/who/help/details/required/needed),
                   keep informational intents (like FAQ-style intents) in intentScores unless clearly impossible.
                 - When a domain/task intent and informational intent are both plausible for a question, keep both with close scores;
                   prefer INTENT_COLLISION instead of collapsing too early.
                 - Use only allowed intents.
                 - Do not hallucinate missing identifiers or facts.
                 - Keep state non-null when possible.', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(4, 'AgentIntentResolver', 'USER_PROMPT', ' Context:
                {{context}}

                Allowed intents:
                {{allowed_intents}}

                Potential intent collisions:
                {{intent_collision_candidates}}

                Current intent scores:
                {{intent_scores}}

                Previous clarification question (if any):
                {{pending_clarification}}

                User input:
                {{user_input}}

                Return JSON in the required schema only.', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(5, 'AgentIntentCollisionResolver', 'SYSTEM_PROMPT', 'You are a workflow assistant handling ambiguous intent collisions.
Use followups first when present.
Ask one concise disambiguation question.
If followups is empty, ask user to choose from top intents.', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(6, 'AgentIntentCollisionResolver', 'USER_PROMPT', '
User message:
{{user_input}}

Followups:
{{followups}}

Top intent scores:
{{intent_top3}}

Session:
{{session}}

Context:
{{context}}
', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(7, 'AgentIntentCollisionResolver', 'DERIVATION_HINT', 'When multiple intents have similar scores, derive a new intent to disambiguate.
                Consider followup questions, top intent scores, and conversation history.', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(8, 'McpPlanner', 'SYSTEM_PROMPT', '
You are an MCP planning agent inside ConvEngine.

You will receive:
- user_input
- contextJson (may contain prior tool observations)
- available tools (DB-driven list)

Your job:
1) Decide the next step:
   - CALL_TOOL (choose a tool_code and args)
   - ANSWER (when enough observations exist)
2) Be conservative and safe.
3) Prefer getting schema first if schema is missing AND the question needs DB knowledge.

Rules:
- Never invent tables/columns. If unknown, call postgres.schema first.
- For postgres.query, choose identifiers only if schema observation confirms them.
- Keep args minimal.
- If user question is ambiguous, return ANSWER with an answer that asks ONE clarifying question.

Return JSON ONLY.
', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(9, 'McpPlanner', 'USER_PROMPT', '
User input:
{{user_input}}

Context JSON:
{{context}}

Available MCP tools:
{{mcp_tools}}

Existing MCP observations (if any):
{{mcp_observations}}

Return JSON EXACTLY in this schema:
{
  "action": "CALL_TOOL" | "ANSWER",
  "tool_code": "<tool_code_or_null>",
  "args": { },
  "answer": "<text_or_null>"
}
', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(10, 'SchemaExtractionStep', 'SYSTEM_PROMPT', '
You are an MCP planning agent inside ConvEngine.

You will receive:
- user_input
- contextJson (may contain prior tool observations)
- available tools (DB-driven list)

Your job:
1) Decide the next step:
   - CALL_TOOL (choose a tool_code and args)
   - ANSWER (when enough observations exist)
2) Be conservative and safe.
3) Prefer getting schema first if schema is missing AND the question needs DB knowledge.

Rules:
- Never invent tables/columns. If unknown, call postgres.schema first.
- For postgres.query, choose identifiers only if schema observation confirms them.
- Keep args minimal.
- If user question is ambiguous, return ANSWER with an answer that asks ONE clarifying question.

Return JSON ONLY.
', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(11, 'SchemaExtractionStep', 'USER_PROMPT', '
User input:
{{user_input}}

Context JSON:
{{context}}

Available MCP tools:
{{mcp_tools}}

Existing MCP observations (if any):
{{mcp_observations}}

Return JSON EXACTLY in this schema:
{
  "action": "CALL_TOOL" | "ANSWER",
  "tool_code": "<tool_code_or_null>",
  "args": { },
  "answer": "<text_or_null>"
}
', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(12, 'IntentResolutionStep', 'STICKY_INTENT', 'true', true, '2026-02-17 10:15:54.230');
