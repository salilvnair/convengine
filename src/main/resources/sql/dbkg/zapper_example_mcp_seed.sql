-- Example DBKG seed for electricity disconnect demo.
-- Uses current request/transaction/inventory/action-status schema only.

INSERT INTO ce_mcp_domain_object (
  domain_object_code, display_name, description, synonyms, confidence_label, metadata_json, rationale, enabled, created_at
)
VALUES
  ('DISCONNECT_REQUEST', 'Disconnect Request', 'Enterprise electricity disconnect request.', 'disconnect request,enterprise disconnect', 'HIGH', '{"primaryIds":["request_id"]}', 'Core request object.', TRUE, NOW()),
  ('DISCONNECT_TRANS', 'Disconnect Transaction', 'Action and status transaction log.', 'status action,transaction', 'HIGH', '{"primaryIds":["id"]}', 'Tracks workflow state changes.', TRUE, NOW()),
  ('ACTION_STATUS_RULE', 'Action Status Rule', 'Static rule matrix from action to next status.', 'action rule,status rule', 'HIGH', '{"primaryIds":["action_id"]}', 'Rule dictionary for teams.', TRUE, NOW())
ON CONFLICT (domain_object_code) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  description = EXCLUDED.description,
  synonyms = EXCLUDED.synonyms,
  confidence_label = EXCLUDED.confidence_label,
  metadata_json = EXCLUDED.metadata_json,
  rationale = EXCLUDED.rationale,
  enabled = EXCLUDED.enabled;

INSERT INTO ce_mcp_query_template (
  query_code, domain_object_code, tool_code, description, sql_template,
  required_params_json, optional_params_json, output_schema_json,
  policy_mode, priority, enabled, rationale, created_at
)
VALUES
  (
    'EX_DISCO_REQUEST_BY_STATUS',
    'DISCONNECT_REQUEST',
    'QUERY_TEMPLATE_EXECUTOR',
    'List disconnect requests by status.',
    'SELECT request_id, customer_name, customer_id, feeder_id, transformer_connection_id, status, updated_at\nFROM zp_disco_request\nWHERE (:status IS NULL OR status = :status)\nORDER BY updated_at DESC\nLIMIT :limit',
    '["limit"]',
    '["status"]',
    '{"fields":["request_id","customer_name","customer_id","feeder_id","transformer_connection_id","status","updated_at"]}',
    'READ_ONLY_STRICT',
    10,
    TRUE,
    'Primary request lookup.',
    NOW()
  ),
  (
    'EX_DISCO_TRANS_TIMELINE',
    'DISCONNECT_TRANS',
    'QUERY_TEMPLATE_EXECUTOR',
    'Timeline with action-rule expansion.',
    'SELECT t.id, t.request_id, t.action_id, a.action_value, t.status, a.nxt_status, a.team, t.disconnect_order_no, t.logged_user_id, t.notes_text, t.created_at\nFROM zp_disco_trans_data t\nJOIN zp_action_status a ON a.action_id = t.action_id\nWHERE t.request_id = :request_id\nORDER BY t.created_at DESC\nLIMIT :limit',
    '["request_id","limit"]',
    '[]',
    '{"fields":["id","request_id","action_id","action_value","status","nxt_status","team","disconnect_order_no","logged_user_id","notes_text","created_at"]}',
    'READ_ONLY_STRICT',
    20,
    TRUE,
    'Request transaction timeline.',
    NOW()
  )
ON CONFLICT (query_code) DO UPDATE SET
  domain_object_code = EXCLUDED.domain_object_code,
  tool_code = EXCLUDED.tool_code,
  description = EXCLUDED.description,
  sql_template = EXCLUDED.sql_template,
  required_params_json = EXCLUDED.required_params_json,
  optional_params_json = EXCLUDED.optional_params_json,
  output_schema_json = EXCLUDED.output_schema_json,
  policy_mode = EXCLUDED.policy_mode,
  priority = EXCLUDED.priority,
  enabled = EXCLUDED.enabled,
  rationale = EXCLUDED.rationale;
