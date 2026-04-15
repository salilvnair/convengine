-- Semantic V2 bootstrap data for electricity disconnect flow.

DELETE FROM ce_output_schema
WHERE intent_code = 'SEMANTIC_QUERY'
  AND state_code IN ('ANALYZE', 'ANY');

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

INSERT INTO ce_semantic_join_hint (base_table, join_table, priority, enabled)
VALUES
  ('zp_disco_request', 'zp_inventory_data', 100, true),
  ('zp_disco_request', 'zp_disco_trans_data', 110, true),
  ('zp_disco_trans_data', 'zp_action_status', 120, true)
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
  ('ACTION_RULE', 'ENTITY', 'Action to status transition rule', 'action,status,transition,rule', true, 100),
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
  '["requestId","customerId","customerName","feederId","transformerConnectionId","status","actionId","disconnectOrderNo"]'::jsonb,
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
