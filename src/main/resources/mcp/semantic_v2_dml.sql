-- Semantic V2 consolidated bootstrap DML (Postgres)
-- Includes MCP planner/control-table seeds + semantic metadata seeds.


-- -----------------------------------------------------------------------------
-- Cleanup (idempotent)
-- -----------------------------------------------------------------------------
DELETE FROM ce_verbose WHERE intent_code = 'SEMANTIC_QUERY';
DELETE FROM ce_rule WHERE intent_code = 'SEMANTIC_QUERY';
DELETE FROM ce_response WHERE intent_code = 'SEMANTIC_QUERY';
DELETE FROM ce_prompt_template WHERE intent_code = 'SEMANTIC_QUERY';
DELETE FROM ce_output_schema WHERE intent_code = 'SEMANTIC_QUERY';
DELETE FROM ce_intent_classifier WHERE intent_code = 'SEMANTIC_QUERY';
DELETE FROM ce_mcp_planner WHERE planner_id = 5401;
DELETE FROM ce_intent WHERE intent_code = 'SEMANTIC_QUERY';

-- Keep tool ids deterministic for demo setup.
DELETE FROM ce_mcp_tool WHERE tool_code IN (
  'db.semantic.interpret',
  'db.semantic.query',
  'postgres.query'
);

-- -----------------------------------------------------------------------------
-- Intent + classifier
-- -----------------------------------------------------------------------------
INSERT INTO ce_intent (intent_code, description, priority, enabled, display_name, llm_hint)
VALUES (
  'SEMANTIC_QUERY',
  'Semantic query pipeline with interpret -> query -> postgres execution.',
  45,
  true,
  'Semantic Query',
  'Use semantic v2 pipeline and ask clarification on ambiguity before SQL execution.'
);

INSERT INTO ce_intent_classifier (intent_code, state_code, rule_type, pattern, priority, enabled, description)
VALUES
(
  'SEMANTIC_QUERY',
  'UNKNOWN',
  'REGEX',
  '(?i)\\b(disconnect|electricity|feeder|transformer|inventory|action|status|request|semantic|query|sql)\\b',
  40,
  true,
  'Classifier for semantic electricity disconnect workflow queries'
);

-- -----------------------------------------------------------------------------
-- Planner / prompt / response
-- -----------------------------------------------------------------------------

INSERT INTO ce_output_schema (intent_code, state_code, schema_type, json_schema, description, enabled, priority)
VALUES
(
  'SEMANTIC_QUERY',
  'ANALYZE',
  'SEMANTIC_INTERPRET',
  '{
    "type":"object",
    "required":["canonicalIntent","confidence","needsClarification","clarificationQuestion","placeholderValue","clarificationResolved","selectedOptionKey","clarificationAnswerText","ambiguities","trace"],
    "properties":{
      "canonicalIntent":{
        "type":"object",
        "required":["intent","entity","queryClass","filters","timeRange","sort","limit"],
        "properties":{
          "intent":{"type":"string"},
          "entity":{"type":"string"},
          "queryClass":{"type":"string"},
          "filters":{
            "type":"array",
            "items":{
              "type":"object",
              "required":["field","op","value"],
              "properties":{
                "field":{"type":"string"},
                "op":{"type":"string"},
                "value":{"type":["string","number","boolean","null"]}
              },
              "additionalProperties":false
            }
          },
          "timeRange":{
            "type":"object",
            "required":["kind","value","timezone","from","to"],
            "properties":{
              "kind":{"type":["string","null"]},
              "value":{"type":["string","null"]},
              "timezone":{"type":["string","null"]},
              "from":{"type":["string","null"]},
              "to":{"type":["string","null"]}
            },
            "additionalProperties":false
          },
          "sort":{
            "type":"array",
            "items":{
              "type":"object",
              "required":["field","direction"],
              "properties":{
                "field":{"type":"string"},
                "direction":{"type":"string"}
              },
              "additionalProperties":false
            }
          },
          "limit":{"type":["integer","null"]}
        },
        "additionalProperties":false
      },
      "confidence":{"type":"number"},
      "needsClarification":{"type":"boolean"},
      "clarificationQuestion":{"type":["string","null"]},
      "placeholderValue":{"type":["string","null"]},
      "clarificationResolved":{"type":"boolean"},
      "selectedOptionKey":{"type":["string","null"]},
      "clarificationAnswerText":{"type":["string","null"]},
      "ambiguities":{
        "type":"array",
        "items":{
          "type":"object",
          "required":["type","code","message","required","options"],
          "properties":{
            "type":{"type":"string"},
            "code":{"type":"string"},
            "message":{"type":"string"},
            "required":{"type":"boolean"},
            "options":{
              "type":"array",
              "items":{
                "type":"object",
                "required":["key","label","confidence"],
                "properties":{
                  "key":{"type":"string"},
                  "label":{"type":"string"},
                  "confidence":{"type":"number"}
                },
                "additionalProperties":false
              }
            }
          },
          "additionalProperties":false
        }
      },
      "trace":{
        "type":"object",
        "required":["normalizations","parser","sanitized","question","clarificationThreshold"],
        "properties":{
          "normalizations":{"type":"array","items":{"type":"string"}},
          "parser":{"type":["string","null"]},
          "sanitized":{"type":["boolean","null"]},
          "question":{"type":["string","null"]},
          "clarificationThreshold":{"type":["number","null"]}
        },
        "additionalProperties":false
      }
    },
    "additionalProperties":false
  }',
  'Output schema for db.semantic.interpret (ANALYZE)',
  true,
  10
),
(
  'SEMANTIC_QUERY',
  'ANY',
  'SEMANTIC_INTERPRET',
  '{
    "type":"object",
    "required":["canonicalIntent","confidence","needsClarification","clarificationQuestion","placeholderValue","clarificationResolved","selectedOptionKey","clarificationAnswerText","ambiguities","trace"],
    "properties":{
      "canonicalIntent":{
        "type":"object",
        "required":["intent","entity","queryClass","filters","timeRange","sort","limit"],
        "properties":{
          "intent":{"type":"string"},
          "entity":{"type":"string"},
          "queryClass":{"type":"string"},
          "filters":{
            "type":"array",
            "items":{
              "type":"object",
              "required":["field","op","value"],
              "properties":{
                "field":{"type":"string"},
                "op":{"type":"string"},
                "value":{"type":["string","number","boolean","null"]}
              },
              "additionalProperties":false
            }
          },
          "timeRange":{
            "type":"object",
            "required":["kind","value","timezone","from","to"],
            "properties":{
              "kind":{"type":["string","null"]},
              "value":{"type":["string","null"]},
              "timezone":{"type":["string","null"]},
              "from":{"type":["string","null"]},
              "to":{"type":["string","null"]}
            },
            "additionalProperties":false
          },
          "sort":{
            "type":"array",
            "items":{
              "type":"object",
              "required":["field","direction"],
              "properties":{
                "field":{"type":"string"},
                "direction":{"type":"string"}
              },
              "additionalProperties":false
            }
          },
          "limit":{"type":["integer","null"]}
        },
        "additionalProperties":false
      },
      "confidence":{"type":"number"},
      "needsClarification":{"type":"boolean"},
      "clarificationQuestion":{"type":["string","null"]},
      "placeholderValue":{"type":["string","null"]},
      "clarificationResolved":{"type":"boolean"},
      "selectedOptionKey":{"type":["string","null"]},
      "clarificationAnswerText":{"type":["string","null"]},
      "ambiguities":{
        "type":"array",
        "items":{
          "type":"object",
          "required":["type","code","message","required","options"],
          "properties":{
            "type":{"type":"string"},
            "code":{"type":"string"},
            "message":{"type":"string"},
            "required":{"type":"boolean"},
            "options":{
              "type":"array",
              "items":{
                "type":"object",
                "required":["key","label","confidence"],
                "properties":{
                  "key":{"type":"string"},
                  "label":{"type":"string"},
                  "confidence":{"type":"number"}
                },
                "additionalProperties":false
              }
            }
          },
          "additionalProperties":false
        }
      },
      "trace":{
        "type":"object",
        "required":["normalizations","parser","sanitized","question","clarificationThreshold"],
        "properties":{
          "normalizations":{"type":"array","items":{"type":"string"}},
          "parser":{"type":["string","null"]},
          "sanitized":{"type":["boolean","null"]},
          "question":{"type":["string","null"]},
          "clarificationThreshold":{"type":["number","null"]}
        },
        "additionalProperties":false
      }
    },
    "additionalProperties":false
  }',
  'Output schema for db.semantic.interpret (ANY)',
  true,
  20
);

INSERT INTO ce_prompt_template
(intent_code, state_code, output_format, system_prompt, user_prompt, temperature, interaction_mode, interaction_contract, enabled)
VALUES
(
  'SEMANTIC_QUERY',
  'ANALYZE',
  'SEMANTIC_INTERPRET',
  'You are a semantic interpreter for business analytics.\nConvert user text into canonical business intent JSON.\n\nHard rules:\n- Return JSON only.\n- Never return SQL.\n- Never return table names, column names, or joins.\n- Use only business field names present in semantic_fields.\n- Never output physical DB names like customer_name, request_type, service_type, created_date.\n- If user asks with an unsupported field, do not invent. Remove that filter/sort and set needsClarification=true.\n- If needsClarification=true, build options only from ambiguity_options. Never invent options.\n- clarificationQuestion MUST be numbered format with 2-4 options:\n  1. <label> (Recommended)\n  2. <label>\n  3. <label>\n  Reply with option number.\n- Keep confidence in range 0..1.\n- Set needsClarification=true when confidence is low or ambiguity exists.',
  'Current date: {{current_date}}\nTimezone: {{current_timezone}}\n\nUser question:\n{{question}}\n\nHints:\n{{hints}}\n\nContext:\n{{semantic_context}}\n\nQuery class key:\n{{query_class_key}}\n\nQuery class defaults:\n{{query_class_config}}\n\nAllowed business fields (strict allowlist):\n{{semantic_fields}}\n\nAllowed values by field:\n{{semantic_allowed_values}}\n\nAmbiguity options (DB-driven):\n{{ambiguity_options}}\n\nRules for clarificationQuestion:\n- Use only labels from ambiguity_options.\n- Include one "(Recommended)" option.\n- Numbered list only (1., 2., 3.).\n- End with: Reply with option number.\n\nExpected shape:\n{\n  "canonicalIntent": {\n    "intent": "LIST_REQUESTS",\n    "entity": "REQUEST",\n    "queryClass": "LIST_REQUESTS",\n    "filters": [{"field":"status","op":"EQ","value":"REJECTED"}],\n    "timeRange": {"kind":"RELATIVE","value":"TODAY","timezone":"{{current_timezone}}"},\n    "sort": [{"field":"createdAt","direction":"DESC"}],\n    "limit": 100\n  },\n  "confidence": 0.0,\n  "needsClarification": false,\n  "clarificationQuestion": null,\n  "ambiguities": [],\n  "trace": {\n    "normalizations": []\n  }\n}',
  0.00,
  'MCP',
  '{"stage":"semantic_interpret"}',
  true
),
(
  'SEMANTIC_QUERY',
  'ANY',
  'SEMANTIC_INTERPRET',
  'You are a semantic interpreter for business analytics.\nConvert user text into canonical business intent JSON.\n\nHard rules:\n- Return JSON only.\n- Never return SQL.\n- Never return table names, column names, or joins.\n- Use only business field names present in semantic_fields.\n- Never output physical DB names like customer_name, request_type, service_type, created_date.\n- If user asks with an unsupported field, do not invent. Remove that filter/sort and set needsClarification=true.\n- If needsClarification=true, build options only from ambiguity_options. Never invent options.\n- clarificationQuestion MUST be numbered format with 2-4 options:\n  1. <label> (Recommended)\n  2. <label>\n  3. <label>\n  Reply with option number.\n- Keep confidence in range 0..1.\n- Set needsClarification=true when confidence is low or ambiguity exists.',
  'Current date: {{current_date}}\nTimezone: {{current_timezone}}\n\nUser question:\n{{question}}\n\nHints:\n{{hints}}\n\nContext:\n{{semantic_context}}\n\nQuery class key:\n{{query_class_key}}\n\nQuery class defaults:\n{{query_class_config}}\n\nAllowed business fields (strict allowlist):\n{{semantic_fields}}\n\nAllowed values by field:\n{{semantic_allowed_values}}\n\nAmbiguity options (DB-driven):\n{{ambiguity_options}}\n\nRules for clarificationQuestion:\n- Use only labels from ambiguity_options.\n- Include one "(Recommended)" option.\n- Numbered list only (1., 2., 3.).\n- End with: Reply with option number.\n\nExpected shape:\n{\n  "canonicalIntent": {\n    "intent": "LIST_REQUESTS",\n    "entity": "REQUEST",\n    "queryClass": "LIST_REQUESTS",\n    "filters": [{"field":"status","op":"EQ","value":"REJECTED"}],\n    "timeRange": {"kind":"RELATIVE","value":"TODAY","timezone":"{{current_timezone}}"},\n    "sort": [{"field":"createdAt","direction":"DESC"}],\n    "limit": 100\n  },\n  "confidence": 0.0,\n  "needsClarification": false,\n  "clarificationQuestion": null,\n  "ambiguities": [],\n  "trace": {\n    "normalizations": []\n  }\n}',
  0.00,
  'MCP',
  '{"stage":"semantic_interpret"}',
  true
),
(
  'SEMANTIC_QUERY',
  'ANALYZE',
  'DERIVED',
  'You are an MCP planner for semantic querying.\nUse pipeline: db.semantic.interpret -> db.semantic.query -> postgres.query.\nIf interpret/query reports needsClarification=true, answer with the clarification question and do not proceed.\nNever skip order. Never invent tool names. Return strict JSON only.\n`action` MUST be exactly CALL_TOOL or ANSWER.\nNever return clarification_required / needs_clarification / clarify.',
  'User input: {{user_input}}\nStandalone query: {{standalone_query}}\nMCP: {{context.mcp}}\nAvailable tools: {{mcp_tools}}\nExisting observations: {{mcp_observations}}',
  0.00,
  'MCP',
  '{"pipeline":["db.semantic.interpret","db.semantic.query","postgres.query"]}',
  true
),
(
  'SEMANTIC_QUERY',
  'FAILED',
  'TEXT',
  'You are a semantic query failure recovery assistant.\nUse only context.mcp.finalAnswer, context.mcp.lifecycle, context.mcp.semantic, and context.mcp.observations.\nDo not invent query results.\nIf failure is due to semantic/preflight mapping, suggest metadata DML templates to fix it.',
  'Context JSON:\n{{context}}\n\nUser input:\n{{user_input}}\n\nReturn exactly these sections:\n1) Failure Stage\n2) Why It Failed\n3) Suggested Metadata Inserts (SQL templates)\n4) Verification Query\n\nRules for SQL suggestions:\n- Infer missing table.column from error text when available.\n- Suggest generic INSERT templates for:\n  - ce_semantic_mapping\n  - ce_semantic_source_table\n  - ce_semantic_source_column\n  - ce_semantic_join_hint (only if join path issue is evident)\n- Keep values as placeholders when uncertain (e.g. <concept_key>, <entity_key>, <query_class_key>).\n- If one concrete missing column is clear, include one concrete INSERT example plus one generic template.\n- Never suggest physical data-row inserts into business transaction tables.',
  0.00,
  'MCP',
  '{"stage":"semantic_failure_recovery"}',
  true
);

INSERT INTO ce_response
(intent_code, state_code, output_format, response_type, exact_text, derivation_hint, json_schema, priority, enabled, description)
VALUES
(
  'SEMANTIC_QUERY',
  'COMPLETED',
  'TEXT',
  'EXACT',
  $$[# th:if="${context != null and context.mcp != null and context.mcp.finalAnswer != null and !#strings.isEmpty(context.mcp.finalAnswer)}"][[${context.mcp.finalAnswer}]][/]
[# th:unless="${context != null and context.mcp != null and context.mcp.finalAnswer != null and !#strings.isEmpty(context.mcp.finalAnswer)}"]Semantic query completed.[/]$$,
  NULL,
  NULL,
  10,
  true,
  'Semantic pipeline completion response'
),
(
  'SEMANTIC_QUERY',
  'FAILED',
  'TEXT',
  'DERIVED',
  NULL,
  'Explain semantic pipeline failure and suggest metadata DML inserts needed to fix semantic/preflight mapping issues.',
  NULL,
  9,
  true,
  'Semantic pipeline failure response'
);

-- -----------------------------------------------------------------------------
-- Rules
-- -----------------------------------------------------------------------------
INSERT INTO ce_rule
(phase, intent_code, state_code, rule_type, match_pattern, "action", action_value, priority, enabled, description)
VALUES
('POST_AGENT_INTENT', 'SEMANTIC_QUERY', 'UNKNOWN', 'REGEX', '.*', 'SET_STATE', 'ANALYZE', 70, true,
 'Bootstrap SEMANTIC_QUERY into ANALYZE when classifier sets UNKNOWN'),
('POST_AGENT_INTENT', 'SEMANTIC_QUERY', 'IDLE', 'REGEX', '.*', 'SET_STATE', 'ANALYZE', 71, true,
 'Bootstrap SEMANTIC_QUERY into ANALYZE from IDLE'),
('POST_AGENT_MCP', 'SEMANTIC_QUERY', 'ANALYZE', 'JSON_PATH',
 '$[?(@.context.mcp.lifecycle.error==true || @.context.mcp.lifecycle.blocked==true || @.context.mcp.lifecycle.status == ''TOOL_ERROR'' || @.context.mcp.lifecycle.outcome == ''ERROR'')]',
 'SET_STATE', 'FAILED', 72, true,
 'Move SEMANTIC_QUERY to FAILED on MCP tool failure'),
('POST_AGENT_MCP', 'SEMANTIC_QUERY', 'ANALYZE', 'JSON_PATH',
 '$[?(@.context.mcp.finalAnswer != ''null'' && @.context.mcp.finalAnswer != null && @.context.mcp.finalAnswer != ''''
      && (@.context.pending_clarification == null || @.context.pending_clarification.question == null || @.context.pending_clarification.question == '''')
      && (@.context.mcp.semantic.semanticClarificationRequired == null || @.context.mcp.semantic.semanticClarificationRequired == false)
      && (@.context.mcp.semantic.clarification.required == null || @.context.mcp.semantic.clarification.required == false)
 )]',
 'SET_STATE', 'COMPLETED', 73, true,
 'Move SEMANTIC_QUERY to COMPLETED only when no clarification is pending'),
('PRE_RESPONSE_RESOLUTION', 'SEMANTIC_QUERY', 'ANY', 'REGEX', '(?i)\\b(reset|restart|start over)\\b', 'SET_STATE', 'IDLE', 74, true,
 'Allow reset for semantic flow');

-- -----------------------------------------------------------------------------
-- Verbose diagnostics
-- -----------------------------------------------------------------------------
INSERT INTO ce_verbose
(intent_code, state_code, step_match, step_value, determinant, rule_id, tool_code, message, error_message, priority, enabled, created_at)
VALUES
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'McpToolStep', 'MCP_DUPLICATE_TOOL_CALL_SUPPRESSED', NULL, 'db.semantic.query',
 'Suppressed duplicate semantic tool call in same turn.',
 'Duplicate semantic tool call suppression failed.',
 4, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticInterpretService', 'INTERPRET_DONE', NULL, 'db.semantic.interpret',
 'Interpret stage completed.',
 'Interpret stage failed.',
 11, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticLlmQueryService', 'SEMANTIC_QUERY_LLM_OUTPUT', NULL, 'db.semantic.query',
 'LLM query stage completed.',
 'LLM query stage failed.',
 11, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'PostgresQueryToolHandler', 'SQL_EXECUTED', NULL, 'postgres.query',
 'postgres.query executed.',
 'postgres.query failed.',
 11, true, now());

-- -----------------------------------------------------------------------------
-- MCP tools + planner
-- -----------------------------------------------------------------------------
INSERT INTO ce_mcp_tool (tool_id, tool_code, tool_group, intent_code, state_code, enabled, description)
VALUES
(9401, 'db.semantic.interpret', 'DB', 'SEMANTIC_QUERY', 'ANALYZE', true, 'Interpret user query into canonical business intent.'),
(9403, 'db.semantic.query', 'DB', 'SEMANTIC_QUERY', 'ANALYZE', true, 'Agent-2 SQL builder from canonical intent (LLM).'),
(9404, 'postgres.query', 'DB', 'SEMANTIC_QUERY', 'ANALYZE', true, 'Execute read-only SQL with parameters.'),
(9405, 'db.semantic.embed.refresh', 'DB', 'SEMANTIC_QUERY', 'ANALYZE', true, 'Refresh ce_semantic_concept_embedding vectors.');

INSERT INTO ce_mcp_planner (planner_id, intent_code, state_code, system_prompt, user_prompt, enabled, created_at)
VALUES
(
  5401,
  'SEMANTIC_QUERY',
  'ANALYZE',
  'You are an MCP planning agent for semantic v2 DB querying.\nUse exact chain:\n1) db.semantic.interpret\n2) db.semantic.query\n3) postgres.query\nIf interpret/query says needsClarification=true, stop and ANSWER with clarificationQuestion.\nDo not skip or reorder tools.\nReturn strict JSON only.\n`action` MUST be exactly CALL_TOOL or ANSWER.\nNever return clarification_required / needs_clarification / clarify.',
  'User input:\n{{user_input}}\n\nStandalone query:\n{{standalone_query}}\n\nMCP:\n{{context.mcp}}\n\nAvailable tools:\n{{mcp_tools}}\n\nExisting MCP observations:\n{{mcp_observations}}\n\nReturn strict JSON:\n{\n  "action":"CALL_TOOL" | "ANSWER",\n  "tool_code":"<tool_code_or_null>",\n  "args":{},\n  "answer":"<text_or_null>",\n  "operation_tag":"<POLICY_RESTRICTED_OPERATION_or_null>"\n}\n`action` MUST be exactly CALL_TOOL or ANSWER. No other value is allowed.',
  true,
  now()
)
ON CONFLICT (planner_id) DO UPDATE
SET
  intent_code = EXCLUDED.intent_code,
  state_code = EXCLUDED.state_code,
  system_prompt = EXCLUDED.system_prompt,
  user_prompt = EXCLUDED.user_prompt,
  enabled = EXCLUDED.enabled;

-- -----------------------------------------------------------------------------
-- Semantic V2 metadata bootstrap (from mcp/semantic_v2/dml.sql)
-- -----------------------------------------------------------------------------
INSERT INTO ce_semantic_model (model_version, database_name, description, enabled)
VALUES
  (1, 'zapper_ops', 'Semantic model for Zapper disconnect workflow analytics and production diagnostics.', true)
ON CONFLICT (database_name, model_version) DO NOTHING;

INSERT INTO ce_semantic_setting (setting_key, setting_value, enabled, priority)
VALUES
  ('default_limit', '100', true, 100),
  ('timezone', 'UTC', true, 110),
  ('sql_dialect', 'postgres', true, 120)
ON CONFLICT (setting_key) DO NOTHING;

INSERT INTO ce_semantic_source_table (table_name, description, enabled, priority)
VALUES
  ('zp_account', 'account master data', true, 100),
  ('zp_customer', 'customer master data', true, 110),
  ('zp_request', 'disconnect request master table', true, 120),
  ('zp_disconnect_order', 'downstream disconnect order tracking', true, 130),
  ('zp_order_downstream_check', 'downstream validation checks', true, 140),
  ('zp_ui_data', 'latest UI operations notes per request', true, 150),
  ('zp_billbank_record', 'bill-bank records for disconnect and cease state', true, 160)
ON CONFLICT (table_name) DO NOTHING;

INSERT INTO ce_semantic_source_column (
  table_name, column_name, data_type, is_primary_key, description, foreign_key_table, foreign_key_column, enabled, priority
)
VALUES
  ('zp_account', 'account_id', 'varchar', true, 'unique account identifier', null, null, true, 100),
  ('zp_account', 'customer_id', 'varchar', false, 'customer owner id', 'zp_customer', 'customer_id', true, 101),
  ('zp_account', 'account_status', 'varchar', false, 'account lifecycle status', null, null, true, 102),
  ('zp_account', 'current_billing_cost', 'numeric', false, 'current billing amount', null, null, true, 103),
  ('zp_account', 'created_at', 'timestamptz', false, 'account creation time', null, null, true, 104),

  ('zp_customer', 'customer_id', 'varchar', true, null, null, null, true, 110),
  ('zp_customer', 'customer_name', 'varchar', false, null, null, null, true, 111),

  ('zp_request', 'zp_request_id', 'varchar', true, null, null, null, true, 120),
  ('zp_request', 'account_id', 'varchar', false, null, 'zp_account', 'account_id', true, 121),
  ('zp_request', 'request_status', 'varchar', false, null, null, null, true, 122),
  ('zp_request', 'requested_at', 'timestamptz', false, null, null, null, true, 123),

  ('zp_disconnect_order', 'don', 'varchar', true, null, null, null, true, 130),
  ('zp_disconnect_order', 'zp_request_id', 'varchar', false, null, 'zp_request', 'zp_request_id', true, 131),
  ('zp_disconnect_order', 'account_id', 'varchar', false, null, 'zp_account', 'account_id', true, 132),
  ('zp_disconnect_order', 'downstream_status', 'varchar', false, null, null, null, true, 133),
  ('zp_disconnect_order', 'zp_disconnect_id', 'varchar', false, null, null, null, true, 134),

  ('zp_order_downstream_check', 'check_id', 'varchar', true, null, null, null, true, 140),
  ('zp_order_downstream_check', 'don', 'varchar', false, null, 'zp_disconnect_order', 'don', true, 141),
  ('zp_order_downstream_check', 'check_code', 'varchar', false, null, null, null, true, 142),
  ('zp_order_downstream_check', 'check_status', 'varchar', false, null, null, null, true, 143),
  ('zp_order_downstream_check', 'failure_reason', 'varchar', false, null, null, null, true, 144),
  ('zp_order_downstream_check', 'checked_at', 'timestamptz', false, null, null, null, true, 145),

  ('zp_ui_data', 'zp_request_id', 'varchar', true, null, 'zp_request', 'zp_request_id', true, 150),
  ('zp_ui_data', 'zp_asr_team_notes', 'varchar', false, null, null, null, true, 151),

  ('zp_billbank_record', 'billbank_id', 'varchar', true, null, null, null, true, 160),
  ('zp_billbank_record', 'account_id', 'varchar', false, null, 'zp_account', 'account_id', true, 161),
  ('zp_billbank_record', 'zp_disconnect_id', 'varchar', false, null, null, null, true, 162),
  ('zp_billbank_record', 'record_status', 'varchar', false, null, null, null, true, 163),
  ('zp_billbank_record', 'pending_bill_amount', 'numeric', false, null, null, null, true, 164),
  ('zp_billbank_record', 'updated_at', 'timestamptz', false, null, null, null, true, 165)
ON CONFLICT (table_name, column_name) DO NOTHING;

INSERT INTO ce_semantic_lexicon (term_key, synonym_text, enabled, priority)
VALUES
  ('account', 'contract', true, 100),
  ('account', 'subscription', true, 101),
  ('account', 'billing account', true, 102),
  ('account', 'customer account', true, 103),
  ('disconnect', 'service termination', true, 110),
  ('disconnect', 'cancel service', true, 111),
  ('disconnect', 'disconnection', true, 112),
  ('disconnect', 'cease service', true, 113),
  ('downstream', 'order check', true, 120),
  ('downstream', 'validation', true, 121),
  ('downstream', 'orchestration', true, 122),
  ('billing', 'billbank', true, 130),
  ('billing', 'invoice', true, 131),
  ('billing', 'reconciliation', true, 132)
ON CONFLICT (term_key, synonym_text) DO NOTHING;

INSERT INTO ce_semantic_rule_allowed_table (table_name, enabled, priority)
VALUES
  ('zp_account', true, 100),
  ('zp_customer', true, 110),
  ('zp_request', true, 120),
  ('zp_disconnect_order', true, 130),
  ('zp_order_downstream_check', true, 140),
  ('zp_ui_data', true, 150),
  ('zp_billbank_record', true, 160)
ON CONFLICT (table_name) DO NOTHING;

INSERT INTO ce_semantic_rule_deny_operation (operation_name, enabled, priority)
VALUES
  ('DELETE', true, 100),
  ('UPDATE', true, 110),
  ('DROP', true, 120)
ON CONFLICT (operation_name) DO NOTHING;

INSERT INTO ce_semantic_rule_config (max_result_limit, enabled)
VALUES
  (500, true);

INSERT INTO ce_semantic_join_hint (base_table, join_table, priority, enabled)
VALUES
  ('zp_disco_request', 'zp_inventory_data', 100, true),
  ('zp_disco_request', 'zp_disco_trans_data', 110, true),
  ('zp_disco_trans_data', 'zp_action_status', 120, true),
  ('zp_disco_request', 'zp_disco_request_log', 130, true)
ON CONFLICT DO NOTHING;

INSERT INTO ce_semantic_value_pattern (from_field, to_field, value_starts_with, priority, enabled)
VALUES
  ('requestId', 'feederId', 'REQ-', 100, true),
  ('feederId', 'requestId', 'FD-', 100, true),
  ('transformerConnectionId', 'feederId', 'TX-CN-', 110, true)
ON CONFLICT DO NOTHING;

INSERT INTO ce_semantic_entity (entity_name, description, primary_table, related_tables, synonyms, fields_json, priority, enabled)
VALUES
  ('DisconnectRequest', 'Enterprise electricity disconnect request lifecycle.', 'zp_disco_request',
   'zp_disco_trans_data,zp_inventory_data,zp_action_status',
   'disconnect request,electricity disconnect,service termination',
   '{
      "requestId":{"column":"zp_disco_request.request_id","type":"string","key":true,"searchable":true,"filterable":true},
      "customerName":{"column":"zp_disco_request.customer_name","type":"string","searchable":true,"filterable":true},
      "customerId":{"column":"zp_disco_request.customer_id","type":"string","searchable":true,"filterable":true},
      "feederId":{"column":"zp_disco_request.feeder_id","type":"string","searchable":true,"filterable":true},
      "transformerConnectionId":{"column":"zp_disco_request.transformer_connection_id","type":"string","searchable":true,"filterable":true},
      "status":{"column":"zp_disco_request.status","type":"number","filterable":true,"allowed_values":[0,120,200,404,500,700,710,800,810,835,840,841,850,855]},
      "signedDisconnectDocument":{"column":"zp_disco_request.signed_disconnect_document","type":"boolean"}
    }'::jsonb,
   100, true),
  ('DiscoTransData', 'Status/action transaction trail for Team1 and Team2 flow.', 'zp_disco_trans_data',
   'zp_disco_request,zp_action_status',
   'workflow transaction,assignment trail,approval trail',
   '{
      "id":{"column":"zp_disco_trans_data.id","type":"number","key":true},
      "requestId":{"column":"zp_disco_trans_data.request_id","type":"string","searchable":true,"filterable":true},
      "actionId":{"column":"zp_disco_trans_data.action_id","type":"number","filterable":true},
      "status":{"column":"zp_disco_trans_data.status","type":"number","filterable":true},
      "disconnectOrderNo":{"column":"zp_disco_trans_data.disconnect_order_no","type":"string","searchable":true,"filterable":true},
      "loggedUserId":{"column":"zp_disco_trans_data.logged_user_id","type":"string","searchable":true,"filterable":true},
      "notesText":{"column":"zp_disco_trans_data.notes_text","type":"string","searchable":true},
      "dueDate":{"column":"zp_disco_trans_data.due_date","type":"date"}
    }'::jsonb,
   110, true),
  ('ActionStatus', 'Static action-to-status transition rule table.', 'zp_action_status',
   'zp_disco_trans_data',
   'action rules,workflow rules,status transition matrix',
   '{
      "actionId":{"column":"zp_action_status.action_id","type":"number","key":true},
      "actionValue":{"column":"zp_action_status.action_value","type":"string"},
      "status":{"column":"zp_action_status.status","type":"number"},
      "nextStatus":{"column":"zp_action_status.nxt_status","type":"number"},
      "team":{"column":"zp_action_status.team","type":"number"}
    }'::jsonb,
   120, true),
  ('InventoryData', 'Inventory validation snapshot by request.', 'zp_inventory_data',
   'zp_disco_request',
   '',
   '{
      "inventoryId":{"column":"zp_inventory_data.inventory_id","type":"number","key":true},
      "requestId":{"column":"zp_inventory_data.request_id","type":"string","searchable":true,"filterable":true},
      "feederId":{"column":"zp_inventory_data.feeder_id","type":"string","searchable":true,"filterable":true},
      "transformerConnectionId":{"column":"zp_inventory_data.transformer_connection_id","type":"string","searchable":true,"filterable":true},
      "feederState":{"column":"zp_inventory_data.feeder_state","type":"string","filterable":true}
    }'::jsonb,
   130, true)
ON CONFLICT DO NOTHING;

INSERT INTO ce_semantic_relationship (relationship_name, description, from_table, from_column, to_table, to_column, relation_type, priority, enabled)
VALUES
  ('request_to_inventory', '', 'zp_disco_request', 'request_id', 'zp_inventory_data', 'request_id', 'one_to_one', 100, true),
  ('request_to_trans', '', 'zp_disco_request', 'request_id', 'zp_disco_trans_data', 'request_id', 'one_to_many', 110, true),
  ('action_rule_to_trans', '', 'zp_action_status', 'action_id', 'zp_disco_trans_data', 'action_id', 'one_to_many', 120, true)
ON CONFLICT DO NOTHING;

INSERT INTO ce_semantic_concept (concept_key, concept_kind, description, tags, enabled, priority)
VALUES
  ('DISCONNECT_REQUEST', 'ENTITY', 'Enterprise electricity disconnect request', 'disconnect,request,electricity,enterprise', true, 100),
  ('DISCONNECT_REQUEST_LOG', 'ENTITY', 'Historical disconnect request snapshots for status transition analysis', 'disconnect,request,history,log,status,transition', true, 100),
  ('ACTION_RULE', 'ENTITY', 'Action to status transition rule', 'action,status,transition,rule', true, 100),
  ('STATUS_TRANSITION', 'INTENT', 'Status transitioned from one value to another', 'status,transition,from,to,history', true, 100),
  ('STATUS_TEAM1_QUEUE', 'STATUS', 'Assigned to Team1 queue', '700,team1 queue', true, 100),
  ('STATUS_TEAM2_QUEUE', 'STATUS', 'Assigned to Team2 queue', '800,team2 queue', true, 100),
  ('STATUS_TEAM2_SELF', 'STATUS', 'Team2 assigned to self', '810,team2 self', true, 100),
  ('STATUS_APPROVE_SUBMIT', 'STATUS', 'Approved and submitted', '835,approve submit', true, 100)
ON CONFLICT (concept_key) DO NOTHING;

INSERT INTO ce_semantic_synonym (synonym_text, concept_key, domain_key, confidence_score, enabled, priority)
VALUES
  ('disconnect electricity', 'DISCONNECT_REQUEST', 'demo_electricity', 1.0, true, 100),
  ('disconnect request', 'DISCONNECT_REQUEST', 'demo_electricity', 0.99, true, 101),
  ('customer', 'DISCONNECT_REQUEST', 'demo_electricity', 0.95, true, 102),
  ('customer name', 'DISCONNECT_REQUEST', 'demo_electricity', 0.95, true, 103),
  ('status transition', 'STATUS_TRANSITION', 'demo_electricity', 1.0, true, 100),
  ('went from', 'STATUS_TRANSITION', 'demo_electricity', 1.0, true, 101),
  ('from status', 'STATUS_TRANSITION', 'demo_electricity', 0.98, true, 102),
  ('to status', 'STATUS_TRANSITION', 'demo_electricity', 0.98, true, 103),
  ('status changed from', 'STATUS_TRANSITION', 'demo_electricity', 0.98, true, 104),
  ('status moved from', 'STATUS_TRANSITION', 'demo_electricity', 0.98, true, 105),
  ('team1 queue', 'STATUS_TEAM1_QUEUE', 'demo_electricity', 0.95, true, 100),
  ('team2 queue', 'STATUS_TEAM2_QUEUE', 'demo_electricity', 0.95, true, 100),
  ('team2 self assigned', 'STATUS_TEAM2_SELF', 'demo_electricity', 0.95, true, 100),
  ('approve and submit', 'STATUS_APPROVE_SUBMIT', 'demo_electricity', 1.0, true, 100)
ON CONFLICT DO NOTHING;

INSERT INTO ce_semantic_concept_embedding (
  concept_key, source_text, embedding_text, embedding_model, embedding_version, confidence_score, enabled, priority
) SELECT
  c.concept_key,
  trim(both ' ' from concat_ws(' ',
    lower(coalesce(c.description, '')),
    lower(coalesce(c.tags, '')),
    lower(coalesce(ss.synonyms, '')),
    lower(coalesce(mm.entities, '')),
    lower(coalesce(mm.fields, '')),
    lower(coalesce(mm.query_classes, ''))
  )),
  null,
  null,
  null,
  coalesce(ss.max_confidence, 1.0),
  true,
  coalesce(c.priority, 100)
FROM ce_semantic_concept c
LEFT JOIN (
  SELECT concept_key,
         string_agg(distinct synonym_text, ' ') AS synonyms,
         max(confidence_score) AS max_confidence
  FROM ce_semantic_synonym
  WHERE enabled = true
  GROUP BY concept_key
) ss ON ss.concept_key = c.concept_key
LEFT JOIN (
  SELECT concept_key,
         string_agg(distinct entity_key, ' ') AS entities,
         string_agg(distinct field_key, ' ') AS fields,
         string_agg(distinct coalesce(query_class_key, ''), ' ') AS query_classes
  FROM ce_semantic_mapping
  WHERE enabled = true
  GROUP BY concept_key
) mm ON mm.concept_key = c.concept_key
WHERE c.enabled = true
ON CONFLICT DO NOTHING;

INSERT INTO ce_semantic_mapping (concept_key, entity_key, field_key, mapped_table, mapped_column, operator_type, value_map_json, query_class_key, enabled, priority)
VALUES
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'requestId', 'zp_disco_request', 'request_id', 'EQ', NULL, 'LIST_REQUESTS', true, 90),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'request_id', 'zp_disco_request', 'request_id', 'EQ', NULL, 'LIST_REQUESTS', true, 91),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'customer', 'zp_disco_request', 'customer_name', 'EQ', NULL, 'LIST_REQUESTS', true, 92),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'customerName', 'zp_disco_request', 'customer_name', 'EQ', NULL, 'LIST_REQUESTS', true, 93),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'customer_name', 'zp_disco_request', 'customer_name', 'EQ', NULL, 'LIST_REQUESTS', true, 94),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'customerId', 'zp_disco_request', 'customer_id', 'EQ', NULL, 'LIST_REQUESTS', true, 95),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'customer_id', 'zp_disco_request', 'customer_id', 'EQ', NULL, 'LIST_REQUESTS', true, 96),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'feederId', 'zp_disco_request', 'feeder_id', 'EQ', NULL, 'LIST_REQUESTS', true, 97),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'feeder_id', 'zp_disco_request', 'feeder_id', 'EQ', NULL, 'LIST_REQUESTS', true, 98),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'transformerConnectionId', 'zp_disco_request', 'transformer_connection_id', 'EQ', NULL, 'LIST_REQUESTS', true, 99),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'transformer_connection_id', 'zp_disco_request', 'transformer_connection_id', 'EQ', NULL, 'LIST_REQUESTS', true, 100),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'status', 'zp_disco_request', 'status', 'EQ', NULL, 'LIST_REQUESTS', true, 101),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'updatedAt', 'zp_disco_request', 'updated_at', 'EQ', NULL, 'LIST_REQUESTS', true, 102),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'updated_at', 'zp_disco_request', 'updated_at', 'EQ', NULL, 'LIST_REQUESTS', true, 103),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'createdAt', 'zp_disco_request', 'created_at', 'EQ', NULL, 'LIST_REQUESTS', true, 104),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'created_at', 'zp_disco_request', 'created_at', 'EQ', NULL, 'LIST_REQUESTS', true, 105),
  ('STATUS_TRANSITION', 'DISCONNECT_REQUEST', 'fromStatus', 'zp_disco_request_log', 'status', 'EQ', NULL, 'LIST_REQUESTS', true, 106),
  ('STATUS_TRANSITION', 'DISCONNECT_REQUEST', 'toStatus', 'zp_disco_request_log', 'status', 'EQ', NULL, 'LIST_REQUESTS', true, 107),
  ('STATUS_TEAM1_QUEUE', 'DISCONNECT_REQUEST', 'status', 'zp_disco_request', 'status', 'EQ', '{"STATUS_TEAM1_QUEUE":[700]}'::jsonb, 'LIST_REQUESTS', true, 100),
  ('STATUS_TEAM2_QUEUE', 'DISCONNECT_REQUEST', 'status', 'zp_disco_request', 'status', 'EQ', '{"STATUS_TEAM2_QUEUE":[800]}'::jsonb, 'LIST_REQUESTS', true, 100),
  ('STATUS_TEAM2_SELF', 'DISCONNECT_TRANS', 'status', 'zp_disco_trans_data', 'status', 'EQ', '{"STATUS_TEAM2_SELF":[810]}'::jsonb, 'LIST_REQUESTS', true, 100),
  ('STATUS_APPROVE_SUBMIT', 'DISCONNECT_TRANS', 'status', 'zp_disco_trans_data', 'status', 'EQ', '{"STATUS_APPROVE_SUBMIT":[835,840]}'::jsonb, 'LIST_REQUESTS', true, 100),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'requestId', 'zp_disco_request', 'request_id', 'EQ', NULL, 'AGGREGATE', true, 290),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'customerName', 'zp_disco_request', 'customer_name', 'ILIKE', NULL, 'AGGREGATE', true, 291),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'customerId', 'zp_disco_request', 'customer_id', 'EQ', NULL, 'AGGREGATE', true, 292),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'status', 'zp_disco_request', 'status', 'EQ', NULL, 'AGGREGATE', true, 293),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'assignedTeam', 'zp_disco_request', 'assigned_team', 'EQ', NULL, 'AGGREGATE', true, 294),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'createdAt', 'zp_disco_request', 'created_at', 'EQ', NULL, 'AGGREGATE', true, 295),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'created_at', 'zp_disco_request', 'created_at', 'EQ', NULL, 'AGGREGATE', true, 296),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'updatedAt', 'zp_disco_request', 'updated_at', 'EQ', NULL, 'AGGREGATE', true, 297),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST', 'updated_at', 'zp_disco_request', 'updated_at', 'EQ', NULL, 'AGGREGATE', true, 298)
ON CONFLICT DO NOTHING;

INSERT INTO ce_semantic_join_path (left_entity_key, right_entity_key, join_expression, join_priority, confidence_score, enabled)
VALUES
  ('DISCONNECT_REQUEST', 'DISCO_TRANS_DATA', 'zp_disco_request.request_id = zp_disco_trans_data.request_id', 100, 1.0, true),
  ('DISCONNECT_REQUEST', 'INVENTORY_DATA', 'zp_disco_request.request_id = zp_inventory_data.request_id', 110, 1.0, true),
  ('DISCONNECT_REQUEST', 'DISCONNECT_REQUEST_LOG', 'zp_disco_request.request_id = zp_disco_request_log.request_id', 115, 1.0, true),
  ('DISCO_TRANS_DATA', 'ACTION_RULE', 'zp_disco_trans_data.action_id = zp_action_status.action_id', 120, 1.0, true)
ON CONFLICT DO NOTHING;

INSERT INTO ce_semantic_query_class (
  query_class_key,
  description,
  base_table_name,
  ast_skeleton_json,
  allowed_filter_fields_json,
  default_select_fields_json,
  default_sort_fields_json,
  enabled,
  priority
) VALUES (
  'LIST_REQUESTS',
  'List enterprise electricity disconnect requests',
  'zp_disco_request',
  '{"queryClass":"LIST_REQUESTS"}'::jsonb,
  '["requestId","customerId","customerName","feederId","transformerConnectionId","status","actionId","disconnectOrderNo","fromStatus","toStatus"]'::jsonb,
  '["requestId","customerName","customerId","feederId","transformerConnectionId","status","updatedAt"]'::jsonb,
  '["updatedAt DESC"]'::jsonb,
  true,
  100
)
ON CONFLICT (query_class_key) DO UPDATE SET
  description = EXCLUDED.description,
  base_table_name = EXCLUDED.base_table_name,
  ast_skeleton_json = EXCLUDED.ast_skeleton_json,
  allowed_filter_fields_json = EXCLUDED.allowed_filter_fields_json,
  default_select_fields_json = EXCLUDED.default_select_fields_json,
  default_sort_fields_json = EXCLUDED.default_sort_fields_json,
  enabled = EXCLUDED.enabled,
  priority = EXCLUDED.priority;

INSERT INTO ce_semantic_query_class (
  query_class_key,
  description,
  base_table_name,
  ast_skeleton_json,
  allowed_filter_fields_json,
  default_select_fields_json,
  default_sort_fields_json,
  enabled,
  priority
) VALUES (
  'AGGREGATE',
  'Count/aggregate disconnect requests',
  'zp_disco_request',
  '{"queryClass":"AGGREGATE"}'::jsonb,
  '["customer","customerName","customerId","status","assignedTeam","assigned_team","createdAt","created_at"]'::jsonb,
  '["requestId"]'::jsonb,
  '["updatedAt DESC"]'::jsonb,
  true,
  100
)
ON CONFLICT (query_class_key) DO UPDATE SET
  description = EXCLUDED.description,
  base_table_name = EXCLUDED.base_table_name,
  ast_skeleton_json = EXCLUDED.ast_skeleton_json,
  allowed_filter_fields_json = EXCLUDED.allowed_filter_fields_json,
  default_select_fields_json = EXCLUDED.default_select_fields_json,
  default_sort_fields_json = EXCLUDED.default_sort_fields_json,
  enabled = EXCLUDED.enabled,
  priority = EXCLUDED.priority;

INSERT INTO ce_semantic_ambiguity_option (
  entity_key,
  query_class_key,
  field_key,
  option_key,
  option_label,
  mapped_filter_json,
  recommended,
  priority,
  enabled
) VALUES
  ('DISCONNECT_REQUEST', 'LIST_REQUESTS', 'customer', 'CUSTOMER_NAME', 'Customer name = {{value}}', '{"field":"customer","op":"EQ","value":"{{value}}"}'::jsonb, true, 100, true),
  ('DISCONNECT_REQUEST', 'LIST_REQUESTS', 'customer', 'CUSTOMER_ID', 'Customer ID = {{value}}', '{"field":"customerId","op":"EQ","value":"{{value}}"}'::jsonb, false, 110, true),
  ('DISCONNECT_REQUEST', 'LIST_REQUESTS', 'customer', 'FEEDER_ID', 'Feeder ID = {{value}}', '{"field":"feederId","op":"EQ","value":"{{value}}"}'::jsonb, false, 120, true),
  ('DISCONNECT_REQUEST', 'AGGREGATE', 'team', 'TEAM_QUEUE_STATUS', 'Queue status = {{value}}', '{"field":"status","op":"EQ","value":"{{value}}"}'::jsonb, true, 100, true),
  ('DISCONNECT_REQUEST', 'AGGREGATE', 'team', 'TEAM_SELF_STATUS', 'Self-assigned status = {{value}}', '{"field":"status","op":"EQ","value":"{{value}}"}'::jsonb, false, 110, true),
  ('DISCONNECT_REQUEST', 'AGGREGATE', 'team', 'ASSIGNED_TEAM', 'Assigned team = {{value}}', '{"field":"assignedTeam","op":"EQ","value":"{{value}}"}'::jsonb, false, 120, true)
ON CONFLICT DO NOTHING;

-- Ensure semantic interpret prompt includes LLM-driven placeholder resolution.
UPDATE ce_prompt_template
SET system_prompt = system_prompt || E'\n- Ambiguity option labels/mapped filters may contain {{value}} placeholder.\n- Infer placeholderValue from user text and return it in JSON.\n- If needsClarification=true, replace {{value}} in clarificationQuestion/options using placeholderValue.',
    user_prompt = user_prompt || E'\n\nAlso return:\n"placeholderValue": "<inferred token or null>"\n\nWhen ambiguity_options contain {{value}}, use placeholderValue to render user-facing options (do not leave {{value}} unresolved).'
WHERE intent_code = 'SEMANTIC_QUERY'
  AND output_format = 'SEMANTIC_INTERPRET'
  AND enabled = true;

-- DB SQL reconcile prompt overrides for PostgresQueryToolHandler.
INSERT INTO ce_config(config_type, config_key, config_value, enabled)
VALUES
  ('PostgresQueryToolHandler', 'DB_SQL_RECONCILE_SYSTEM_PROMPT', 'You are a DB SQL schema/type reconciliation assistant for ConvEngine MCP DB tools.
Validate SQL against provided semantic metadata and runtime DB schema.
Focus on type-safe predicates and parameter compatibility.
Keep query semantics unchanged.
Never invent table/column names.
For numeric columns, prefer CAST(:param AS <numeric-type>) when ambiguity can happen.
For transition-log outputs, deduplicate to one row per (request_id, scenario_id) using DISTINCT ON.
Use deterministic ordering with request_id, scenario_id, to_logged_at DESC, to_log_id DESC.
Preserve named params and return JSON only.', true),
  ('PostgresQueryToolHandler', 'DB_SQL_RECONCILE_USER_PROMPT', 'Candidate SQL:
{{sql_before}}

Params:
{{params_json}}

Preflight diagnostics:
{{preflight_json}}

Runtime schema details:
{{schema_json}}

Semantic hints:
{{semantic_json}}

Task:
- Fix type mismatches and unsafe comparisons.
- Keep joins/filters semantics unchanged.
- Enforce dedup for transition logs: one row per (request_id, scenario_id) via DISTINCT ON + deterministic ordering.
- Keep params unchanged.
- Return SQL only.', true),
  ('PostgresQueryToolHandler', 'DB_SQL_RECONCILE_SCHEMA_JSON', '{
  "type":"object",
  "required":["sql"],
  "properties":{
    "sql":{"type":"string"}
  },
  "additionalProperties":false
}', true)
ON CONFLICT (config_type, config_key) DO UPDATE
SET config_value = EXCLUDED.config_value,
    enabled = EXCLUDED.enabled;
