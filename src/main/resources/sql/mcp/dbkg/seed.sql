-- DBKG seed aligned to electricity disconnect demo schema.
-- This file intentionally excludes legacy transaction models.

INSERT INTO ce_mcp_domain_object (
  domain_object_code,
  display_name,
  description,
  synonyms,
  confidence_label,
  metadata_json,
  rationale,
  enabled,
  created_at
)
VALUES
  ('DISCONNECT_REQUEST', 'Disconnect Request', 'Enterprise electricity disconnect request.', 'disconnect request,feeder disconnect,service termination', 'HIGH', '{"primaryIds":["request_id"]}', 'Primary object for lifecycle tracking.', TRUE, NOW()),
  ('DISCONNECT_TRANS', 'Disconnect Transaction', 'Action and status transaction rows.', 'action,status,transaction,approve submit', 'HIGH', '{"primaryIds":["id"],"foreignKey":"request_id"}', 'Tracks workflow loop and async order id capture.', TRUE, NOW()),
  ('INVENTORY_DATA', 'Inventory Data', 'Inventory validation snapshot for each request.', 'inventory,feeder,transformer,validation', 'HIGH', '{"primaryIds":["inventory_id"],"foreignKey":"request_id"}', 'Validation evidence for request progression.', TRUE, NOW())
ON CONFLICT (domain_object_code) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  description = EXCLUDED.description,
  synonyms = EXCLUDED.synonyms,
  confidence_label = EXCLUDED.confidence_label,
  metadata_json = EXCLUDED.metadata_json,
  rationale = EXCLUDED.rationale,
  enabled = EXCLUDED.enabled;

INSERT INTO ce_mcp_join_path (
  path_name,
  source_system,
  source_table,
  source_column,
  target_system,
  target_table,
  target_column,
  relation_type,
  rationale,
  enabled
)
VALUES
  ('request_to_trans', 'DEMO', 'zp_disco_request', 'request_id', 'DEMO', 'zp_disco_trans_data', 'request_id', 'IDENTITY', 'Request to transaction linkage.', TRUE),
  ('request_to_inventory', 'DEMO', 'zp_disco_request', 'request_id', 'DEMO', 'zp_inventory_data', 'request_id', 'IDENTITY', 'Request to inventory linkage.', TRUE),
  ('trans_to_action_status', 'DEMO', 'zp_disco_trans_data', 'action_id', 'DEMO', 'zp_action_status', 'action_id', 'IDENTITY', 'Transaction to static action rules.', TRUE)
ON CONFLICT (path_name) DO UPDATE SET
  source_system = EXCLUDED.source_system,
  source_table = EXCLUDED.source_table,
  source_column = EXCLUDED.source_column,
  target_system = EXCLUDED.target_system,
  target_table = EXCLUDED.target_table,
  target_column = EXCLUDED.target_column,
  relation_type = EXCLUDED.relation_type,
  rationale = EXCLUDED.rationale,
  enabled = EXCLUDED.enabled;

INSERT INTO ce_mcp_query_template (
  query_code,
  domain_object_code,
  tool_code,
  description,
  sql_template,
  required_params_json,
  optional_params_json,
  output_schema_json,
  policy_mode,
  priority,
  enabled,
  rationale,
  created_at
)
VALUES
  (
    'DISCO_REQUEST_BY_STATUS',
    'DISCONNECT_REQUEST',
    'QUERY_TEMPLATE_EXECUTOR',
    'Lookup requests by status and customer.',
    'SELECT r.request_id, r.customer_name, r.customer_id, r.feeder_id, r.transformer_connection_id, r.status, r.updated_at\nFROM zp_disco_request r\nWHERE (:status IS NULL OR r.status = :status)\n  AND (:customer_id IS NULL OR r.customer_id = :customer_id)\nORDER BY r.updated_at DESC\nLIMIT :limit',
    '["limit"]',
    '["status","customer_id"]',
    '{"primaryKeys":["request_id"],"fields":["request_id","customer_name","customer_id","feeder_id","transformer_connection_id","status","updated_at"]}',
    'READ_ONLY_STRICT',
    10,
    TRUE,
    'Primary request filter query.',
    NOW()
  ),
  (
    'DISCO_TRANS_BY_REQUEST',
    'DISCONNECT_TRANS',
    'QUERY_TEMPLATE_EXECUTOR',
    'Get transaction trail for a request.',
    'SELECT t.id, t.request_id, t.action_id, a.action_value, t.status, a.nxt_status, a.team, t.disconnect_order_no, t.logged_user_id, t.notes_text, t.created_at\nFROM zp_disco_trans_data t\nJOIN zp_action_status a ON a.action_id = t.action_id\nWHERE t.request_id = :request_id\nORDER BY t.created_at DESC\nLIMIT :limit',
    '["request_id","limit"]',
    '[]',
    '{"primaryKeys":["id"],"fields":["id","request_id","action_id","action_value","status","nxt_status","team","disconnect_order_no","logged_user_id","notes_text","created_at"]}',
    'READ_ONLY_STRICT',
    20,
    TRUE,
    'Transaction timeline with rule expansion.',
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
