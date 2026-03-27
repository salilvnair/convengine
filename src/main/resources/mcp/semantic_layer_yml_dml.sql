-- YAML-only semantic seed DML (derived from semantic-layer.yml content).
-- Domain: electricity disconnect workflow tables under schema v2.

INSERT INTO ce_semantic_model (model_version, database_name, description, enabled)
VALUES
  (1, 'demo_electricity_ops', 'Semantic model for electricity disconnect workflow diagnostics and operations.', true)
ON CONFLICT (database_name, model_version) DO NOTHING;

INSERT INTO ce_semantic_setting (setting_key, setting_value, enabled, priority)
VALUES
  ('default_limit', '100', true, 100),
  ('timezone', 'UTC', true, 110),
  ('sql_dialect', 'postgres', true, 120)
ON CONFLICT (setting_key) DO NOTHING;

INSERT INTO ce_semantic_source_table (table_name, description, enabled, priority)
VALUES
  ('zp_action_status', 'Action to status transition rules', true, 100),
  ('zp_disco_request', 'Disconnect request lifecycle master', true, 110),
  ('zp_disco_request_log', 'Historical snapshots of request status transitions', true, 120),
  ('zp_disco_trans_data', 'Transaction trail for workflow actions', true, 130),
  ('zp_inventory_data', 'Inventory validation data for disconnect flow', true, 140)
ON CONFLICT (table_name) DO NOTHING;

INSERT INTO ce_semantic_source_column (
  table_name, column_name, data_type, is_primary_key, description, foreign_key_table, foreign_key_column, enabled, priority
)
VALUES
  ('zp_action_status', 'action_id', 'integer', true, 'Action id', null, null, true, 100),
  ('zp_action_status', 'action_value', 'varchar', false, 'Action label', null, null, true, 101),
  ('zp_action_status', 'status', 'integer', false, 'Current status', null, null, true, 102),
  ('zp_action_status', 'nxt_status', 'integer', false, 'Next status', null, null, true, 103),
  ('zp_action_status', 'team', 'integer', false, 'Team id', null, null, true, 104),
  ('zp_action_status', 'enabled', 'boolean', false, 'Rule enabled', null, null, true, 105),

  ('zp_disco_request', 'request_id', 'varchar', true, 'Request id', null, null, true, 110),
  ('zp_disco_request', 'customer_name', 'varchar', false, 'Customer name', null, null, true, 111),
  ('zp_disco_request', 'customer_id', 'varchar', false, 'Customer id', null, null, true, 112),
  ('zp_disco_request', 'feeder_id', 'varchar', false, 'Feeder id', null, null, true, 113),
  ('zp_disco_request', 'transformer_connection_id', 'varchar', false, 'Transformer connection id', null, null, true, 114),
  ('zp_disco_request', 'plan_id', 'varchar', false, 'Plan id', null, null, true, 115),
  ('zp_disco_request', 'address_location', 'varchar', false, 'Address/location', null, null, true, 116),
  ('zp_disco_request', 'signed_disconnect_document', 'boolean', false, 'Signed document flag', null, null, true, 117),
  ('zp_disco_request', 'status', 'integer', false, 'Workflow status', null, null, true, 118),
  ('zp_disco_request', 'status_reason', 'varchar', false, 'Status reason', null, null, true, 119),
  ('zp_disco_request', 'assigned_team', 'varchar', false, 'Assigned team', null, null, true, 120),
  ('zp_disco_request', 'created_at', 'timestamptz', false, 'Created at', null, null, true, 121),
  ('zp_disco_request', 'updated_at', 'timestamptz', false, 'Updated at', null, null, true, 122),

  ('zp_disco_request_log', 'scenario_id', 'varchar', false, 'Scenario identifier', null, null, true, 130),
  ('zp_disco_request_log', 'request_id', 'varchar', false, 'Request id snapshot', 'zp_disco_request', 'request_id', true, 131),
  ('zp_disco_request_log', 'status', 'integer', false, 'Logged status', null, null, true, 132),
  ('zp_disco_request_log', 'status_reason', 'varchar', false, 'Logged status reason', null, null, true, 133),
  ('zp_disco_request_log', 'assigned_team', 'varchar', false, 'Logged assigned team', null, null, true, 134),
  ('zp_disco_request_log', 'logged_at', 'timestamptz', false, 'Snapshot logged timestamp', null, null, true, 135),

  ('zp_disco_trans_data', 'request_id', 'varchar', false, 'Request id', 'zp_disco_request', 'request_id', true, 140),
  ('zp_disco_trans_data', 'action_id', 'integer', false, 'Action id', 'zp_action_status', 'action_id', true, 141),
  ('zp_disco_trans_data', 'status', 'integer', false, 'Status at transaction time', null, null, true, 142),
  ('zp_disco_trans_data', 'disconnect_order_no', 'varchar', false, 'Disconnect order number', null, null, true, 143),
  ('zp_disco_trans_data', 'logged_user_id', 'varchar', false, 'User id who performed action', null, null, true, 144),
  ('zp_disco_trans_data', 'notes_text', 'varchar', false, 'Notes', null, null, true, 145),
  ('zp_disco_trans_data', 'due_date', 'date', false, 'Due date', null, null, true, 146),
  ('zp_disco_trans_data', 'created_at', 'timestamptz', false, 'Created at', null, null, true, 147),

  ('zp_inventory_data', 'request_id', 'varchar', false, 'Request id', 'zp_disco_request', 'request_id', true, 150),
  ('zp_inventory_data', 'feeder_id', 'varchar', false, 'Feeder id', null, null, true, 151),
  ('zp_inventory_data', 'transformer_connection_id', 'varchar', false, 'Transformer connection id', null, null, true, 152),
  ('zp_inventory_data', 'customer_id', 'varchar', false, 'Customer id', null, null, true, 153),
  ('zp_inventory_data', 'customer_name', 'varchar', false, 'Customer name', null, null, true, 154),
  ('zp_inventory_data', 'plan_id', 'varchar', false, 'Plan id', null, null, true, 155),
  ('zp_inventory_data', 'address_location', 'varchar', false, 'Address/location', null, null, true, 156),
  ('zp_inventory_data', 'feeder_state', 'varchar', false, 'Feeder state', null, null, true, 157),
  ('zp_inventory_data', 'feeder_closed_date', 'date', false, 'Feeder closed date', null, null, true, 158),
  ('zp_inventory_data', 'last_verified_at', 'timestamptz', false, 'Last verified at', null, null, true, 159),
  ('zp_inventory_data', 'created_at', 'timestamptz', false, 'Created at', null, null, true, 160),
  ('zp_inventory_data', 'updated_at', 'timestamptz', false, 'Updated at', null, null, true, 161)
ON CONFLICT (table_name, column_name) DO NOTHING;

INSERT INTO ce_semantic_entity (entity_name, description, primary_table, related_tables, synonyms, fields_json, priority, enabled)
VALUES
  ('DisconnectRequest', 'Enterprise electricity disconnect request lifecycle.', 'zp_disco_request',
   'zp_disco_trans_data,zp_inventory_data,zp_action_status,zp_disco_request_log',
   'disconnect request,electricity disconnect,service termination,failed disconnect',
   '{
      "requestId":{"column":"zp_disco_request.request_id","type":"string","key":true,"searchable":true,"filterable":true},
      "customerName":{"column":"zp_disco_request.customer_name","type":"string","searchable":true,"filterable":true},
      "customerId":{"column":"zp_disco_request.customer_id","type":"string","searchable":true,"filterable":true},
      "feederId":{"column":"zp_disco_request.feeder_id","type":"string","searchable":true,"filterable":true},
      "transformerConnectionId":{"column":"zp_disco_request.transformer_connection_id","type":"string","searchable":true,"filterable":true},
      "status":{"column":"zp_disco_request.status","type":"number","filterable":true,"allowed_values":[120,404,700,710,800,810,835]},
      "assignedTeam":{"column":"zp_disco_request.assigned_team","type":"string","filterable":true},
      "statusReason":{"column":"zp_disco_request.status_reason","type":"string","searchable":true},
      "createdAt":{"column":"zp_disco_request.created_at","type":"timestamp"},
      "updatedAt":{"column":"zp_disco_request.updated_at","type":"timestamp"}
    }'::jsonb,
   100, true),
  ('DiscoTransData', 'Status/action transaction trail for Team1 and Team2 flow.', 'zp_disco_trans_data',
   'zp_disco_request,zp_action_status',
   'workflow transaction,assignment trail,approval trail,disconnect order',
   '{
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
   'inventory,validation,feeder state',
   '{
      "requestId":{"column":"zp_inventory_data.request_id","type":"string","searchable":true,"filterable":true},
      "feederId":{"column":"zp_inventory_data.feeder_id","type":"string","searchable":true,"filterable":true},
      "transformerConnectionId":{"column":"zp_inventory_data.transformer_connection_id","type":"string","searchable":true,"filterable":true},
      "feederState":{"column":"zp_inventory_data.feeder_state","type":"string","filterable":true}
    }'::jsonb,
   130, true),
  ('DiscoRequestLog', 'Historical status transitions for disconnect requests.', 'zp_disco_request_log',
   'zp_disco_request',
   'history,status transition,request log',
   '{
      "scenarioId":{"column":"zp_disco_request_log.scenario_id","type":"string","filterable":true},
      "requestId":{"column":"zp_disco_request_log.request_id","type":"string","searchable":true,"filterable":true},
      "status":{"column":"zp_disco_request_log.status","type":"number","filterable":true},
      "assignedTeam":{"column":"zp_disco_request_log.assigned_team","type":"string","filterable":true},
      "loggedAt":{"column":"zp_disco_request_log.logged_at","type":"timestamp"}
    }'::jsonb,
   140, true)
ON CONFLICT DO NOTHING;

INSERT INTO ce_semantic_relationship (relationship_name, description, from_table, from_column, to_table, to_column, relation_type, priority, enabled)
VALUES
  ('request_to_inventory', 'request mapped to inventory snapshot', 'zp_disco_request', 'request_id', 'zp_inventory_data', 'request_id', 'one_to_one', 100, true),
  ('request_to_trans', 'request mapped to transaction trail', 'zp_disco_request', 'request_id', 'zp_disco_trans_data', 'request_id', 'one_to_many', 110, true),
  ('action_rule_to_trans', 'action rules joined by action id to transaction trail', 'zp_action_status', 'action_id', 'zp_disco_trans_data', 'action_id', 'one_to_many', 120, true),
  ('request_to_log', 'request mapped to historical request log snapshots', 'zp_disco_request', 'request_id', 'zp_disco_request_log', 'request_id', 'one_to_many', 130, true)
ON CONFLICT DO NOTHING;

INSERT INTO ce_semantic_lexicon (term_key, synonym_text, enabled, priority)
VALUES
  ('disconnect', 'service termination', true, 100),
  ('disconnect', 'cancel request', true, 101),
  ('disconnect', 'disconnection', true, 102),
  ('request', 'disconnect request', true, 110),
  ('request', 'workflow request', true, 111),
  ('inventory', 'feeder state', true, 120),
  ('inventory', 'validation snapshot', true, 121),
  ('status', 'workflow status', true, 130),
  ('status', 'queue state', true, 131),
  ('team', 'team1', true, 140),
  ('team', 'team2', true, 141)
ON CONFLICT (term_key, synonym_text) DO NOTHING;

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
  ('transformerConnectionId', 'feederId', 'TX-CN-', 110, true),
  ('disconnectOrderNo', 'requestId', 'DO-', 120, true)
ON CONFLICT DO NOTHING;

INSERT INTO ce_semantic_rule_allowed_table (table_name, enabled, priority)
VALUES
  ('zp_action_status', true, 100),
  ('zp_disco_request', true, 110),
  ('zp_disco_request_log', true, 120),
  ('zp_disco_trans_data', true, 130),
  ('zp_inventory_data', true, 140)
ON CONFLICT (table_name) DO NOTHING;

INSERT INTO ce_semantic_rule_deny_operation (operation_name, enabled, priority)
VALUES
  ('DELETE', true, 100),
  ('UPDATE', true, 110),
  ('DROP', true, 120),
  ('TRUNCATE', true, 130),
  ('INSERT', true, 140)
ON CONFLICT (operation_name) DO NOTHING;

INSERT INTO ce_semantic_rule_config (max_result_limit, enabled)
VALUES
  (500, true);
