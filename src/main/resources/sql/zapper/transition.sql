-- 1) Ensure physical tables exist in semantic source catalog
INSERT INTO ce_semantic_source_table (table_name, description, enabled, priority) VALUES
  ('zp_disco_request', 'Disconnect request master', true, 100),
  ('zp_disco_request_log', 'Disconnect request status snapshots', true, 110),
  ('zp_disco_trans_data', 'Disconnect transaction trail', true, 120),
  ('zp_inventory_data', 'Inventory snapshot', true, 130),
  ('zp_action_status', 'Action to status rules', true, 140)
ON CONFLICT (table_name) DO UPDATE
SET description = EXCLUDED.description, enabled = EXCLUDED.enabled, priority = EXCLUDED.priority;

-- 2) Ensure critical columns used by generated SQL are known
INSERT INTO ce_semantic_source_column
(table_name, column_name, data_type, is_primary_key, description, foreign_key_table, foreign_key_column, enabled, priority)
VALUES
  ('zp_disco_request','request_id','varchar',true,'Request id',null,null,true,100),
  ('zp_disco_request','customer_name','varchar',false,'Customer name',null,null,true,101),
  ('zp_disco_request','customer_id','varchar',false,'Customer id',null,null,true,102),
  ('zp_disco_request','feeder_id','varchar',false,'Feeder id',null,null,true,103),
  ('zp_disco_request','transformer_connection_id','varchar',false,'Transformer connection id',null,null,true,104),
  ('zp_disco_request','status','int',false,'Current status',null,null,true,105),
  ('zp_disco_request','created_at','timestamptz',false,'Created time',null,null,true,106),
  ('zp_disco_request','updated_at','timestamptz',false,'Updated time',null,null,true,107),
  ('zp_disco_request','assigned_team','varchar',false,'Assigned team',null,null,true,108),

  ('zp_disco_request_log','request_id','varchar',false,'Request id','zp_disco_request','request_id',true,120),
  ('zp_disco_request_log','scenario_id','varchar',false,'Scenario id',null,null,true,121),
  ('zp_disco_request_log','customer_name','varchar',false,'Customer name',null,null,true,122),
  ('zp_disco_request_log','customer_id','varchar',false,'Customer id',null,null,true,123),
  ('zp_disco_request_log','status','int',false,'Status in snapshot',null,null,true,124),
  ('zp_disco_request_log','logged_at','timestamptz',false,'Snapshot time',null,null,true,125),
  ('zp_disco_request_log','updated_at','timestamptz',false,'Legacy snapshot time',null,null,true,126)
ON CONFLICT (table_name, column_name) DO UPDATE
SET data_type = EXCLUDED.data_type,
    is_primary_key = EXCLUDED.is_primary_key,
    description = EXCLUDED.description,
    foreign_key_table = EXCLUDED.foreign_key_table,
    foreign_key_column = EXCLUDED.foreign_key_column,
    enabled = EXCLUDED.enabled,
    priority = EXCLUDED.priority;

-- 3) Join hints for transition SQL
INSERT INTO ce_semantic_join_hint (base_table, join_table, priority, enabled) VALUES
  ('zp_disco_request', 'zp_disco_request_log', 90, true),
  ('zp_disco_request', 'zp_disco_trans_data', 100, true),
  ('zp_disco_request', 'zp_inventory_data', 110, true)
ON CONFLICT DO NOTHING;

-- 4) Add query classes (table-driven, no Java hardcode)
INSERT INTO ce_semantic_query_class
(query_class_key, description, base_table_name, ast_skeleton_json, allowed_filter_fields_json, default_select_fields_json, default_sort_fields_json, enabled, priority)
VALUES
  ('TRANSITION_EVENTUAL',
   'Requests that moved from status A to status B at any later point',
   'zp_disco_request',
   '{"queryClass":"TRANSITION_EVENTUAL"}'::jsonb,
   '["requestId","customerId","customerName","feederId","transformerConnectionId","fromStatus","toStatus","fromLoggedAt","toLoggedAt","createdAt","updatedAt"]'::jsonb,
   '["requestId","customerName","customerId","feederId","transformerConnectionId","status","createdAt","updatedAt"]'::jsonb,
   '["updatedAt DESC"]'::jsonb,
   true, 80),
  ('TRANSITION_DIRECT',
   'Requests that moved directly from status A to status B without intermediate statuses',
   'zp_disco_request',
   '{"queryClass":"TRANSITION_DIRECT"}'::jsonb,
   '["requestId","customerId","customerName","feederId","transformerConnectionId","fromStatus","toStatus","fromLoggedAt","toLoggedAt","createdAt","updatedAt"]'::jsonb,
   '["requestId","customerName","customerId","feederId","transformerConnectionId","status","createdAt","updatedAt"]'::jsonb,
   '["updatedAt DESC"]'::jsonb,
   true, 79)
ON CONFLICT (query_class_key) DO UPDATE
SET description = EXCLUDED.description,
    base_table_name = EXCLUDED.base_table_name,
    ast_skeleton_json = EXCLUDED.ast_skeleton_json,
    allowed_filter_fields_json = EXCLUDED.allowed_filter_fields_json,
    default_select_fields_json = EXCLUDED.default_select_fields_json,
    default_sort_fields_json = EXCLUDED.default_sort_fields_json,
    enabled = EXCLUDED.enabled,
    priority = EXCLUDED.priority;

-- 5) Map transition fields for BOTH new query classes
INSERT INTO ce_semantic_mapping
(concept_key, entity_key, field_key, mapped_table, mapped_column, operator_type, value_map_json, query_class_key, enabled, priority)
VALUES
  ('STATUS_TRANSITION','DISCONNECT_REQUEST','fromStatus','zp_disco_request_log l_from','status','EQ',null,'TRANSITION_EVENTUAL',true,500),
  ('STATUS_TRANSITION','DISCONNECT_REQUEST','toStatus','zp_disco_request_log l_to','status','EQ',null,'TRANSITION_EVENTUAL',true,501),
  ('STATUS_TRANSITION','DISCONNECT_REQUEST','fromLoggedAt','zp_disco_request_log l_from','logged_at','EQ',null,'TRANSITION_EVENTUAL',true,502),
  ('STATUS_TRANSITION','DISCONNECT_REQUEST','toLoggedAt','zp_disco_request_log l_to','logged_at','EQ',null,'TRANSITION_EVENTUAL',true,503),

  ('STATUS_TRANSITION','DISCONNECT_REQUEST','fromStatus','zp_disco_request_log l_from','status','EQ',null,'TRANSITION_DIRECT',true,510),
  ('STATUS_TRANSITION','DISCONNECT_REQUEST','toStatus','zp_disco_request_log l_to','status','EQ',null,'TRANSITION_DIRECT',true,511),
  ('STATUS_TRANSITION','DISCONNECT_REQUEST','fromLoggedAt','zp_disco_request_log l_from','logged_at','EQ',null,'TRANSITION_DIRECT',true,512),
  ('STATUS_TRANSITION','DISCONNECT_REQUEST','toLoggedAt','zp_disco_request_log l_to','logged_at','EQ',null,'TRANSITION_DIRECT',true,513)
ON CONFLICT DO NOTHING;

-- 6) Synonyms to route intent correctly (metadata-only)
INSERT INTO ce_semantic_synonym (synonym_text, concept_key, domain_key, confidence_score, enabled, priority) VALUES
  ('direct transition','STATUS_TRANSITION','demo_electricity',0.99,true,200),
  ('directly moved from','STATUS_TRANSITION','demo_electricity',0.99,true,201),
  ('without intermediate status','STATUS_TRANSITION','demo_electricity',1.00,true,202),
  ('eventually moved from','STATUS_TRANSITION','demo_electricity',0.99,true,203),
  ('went from','STATUS_TRANSITION','demo_electricity',1.00,true,204)
ON CONFLICT DO NOTHING;

-- 1) Source column
INSERT INTO ce_semantic_source_column
(table_name, column_name, data_type, is_primary_key, enabled, priority)
VALUES
('zp_disco_request_log','log_id','bigint',true,true,50)
ON CONFLICT (table_name, column_name) DO NOTHING;

-- 2) Semantic mapping for transition classes
INSERT INTO ce_semantic_mapping
(concept_key, entity_key, field_key, mapped_table, mapped_column, operator_type, query_class_key, enabled, priority)
VALUES
('STATUS_TRANSITION','DISCONNECT_REQUEST','fromLogId','zp_disco_request_log l_from','log_id','EQ','TRANSITION_DIRECT',true,520),
('STATUS_TRANSITION','DISCONNECT_REQUEST','toLogId','zp_disco_request_log l_to','log_id','EQ','TRANSITION_DIRECT',true,521),
('STATUS_TRANSITION','DISCONNECT_REQUEST','fromLogId','zp_disco_request_log l_from','log_id','EQ','TRANSITION_EVENTUAL',true,522),
('STATUS_TRANSITION','DISCONNECT_REQUEST','toLogId','zp_disco_request_log l_to','log_id','EQ','TRANSITION_EVENTUAL',true,523)
ON CONFLICT DO NOTHING;

-- 3) Let query class expose tie-break fields
UPDATE ce_semantic_query_class
SET allowed_filter_fields_json =
  (allowed_filter_fields_json || '["fromLogId","toLogId"]'::jsonb)
WHERE query_class_key IN ('TRANSITION_DIRECT','TRANSITION_EVENTUAL');

