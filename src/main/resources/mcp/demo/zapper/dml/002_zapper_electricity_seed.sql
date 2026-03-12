-- Zapper Electricity Enterprise Disconnect demo seed data

INSERT INTO zp_action_status (action_id, action_value, status, nxt_status, team, enabled)
VALUES
  (1000, 'Assigned to self', 700, 710, 1, true),
  (2000, 'Assign to Team 2', 710, 800, 1, true),
  (3000, 'Cancel Request', 710, 120, 1, true),
  (4000, 'Assigned to self', 800, 810, 2, true),
  (5000, 'Assign to Team 1', 810, 700, 2, true),
  (6000, 'Cancel Request', 810, 120, 2, true),
  (7000, 'Approve and Submit', 810, 835, 2, true)
ON CONFLICT (action_id) DO UPDATE SET
  action_value = EXCLUDED.action_value,
  status = EXCLUDED.status,
  nxt_status = EXCLUDED.nxt_status,
  team = EXCLUDED.team,
  enabled = EXCLUDED.enabled;

INSERT INTO zp_disco_request (
  request_id,
  customer_name,
  customer_id,
  feeder_id,
  transformer_connection_id,
  plan_id,
  address_location,
  signed_disconnect_document,
  status,
  status_reason,
  assigned_team,
  sync_status_code,
  async_status_code,
  soft_disconnect_at,
  hard_disconnect_due_at,
  hard_disconnect_at,
  feeder_closed_at,
  created_at,
  updated_at
) VALUES
  ('REQ-20260311-0001', 'UPS', 'CUST102', 'FD-IRV-1022', 'TX-CN-0091', 'PLAN-E-100', 'Irving Office, TX', TRUE, 700, 'Nightly cron completed and moved from 200 to Team1 queue.', 'TEAM1', 200, NULL, NULL, NOW() + INTERVAL '2 day', NULL, NULL, NOW() - INTERVAL '10 hour', NOW()),
  ('REQ-20260311-0002', 'UPS', 'CUST102', 'FD-COP-2210', 'TX-CN-0033', 'PLAN-E-100', 'Coppell Office, TX', TRUE, 810, 'Team2 picked the request and is ready for final approval.', 'TEAM2', 200, NULL, NOW() - INTERVAL '1 day', NOW() + INTERVAL '1 day', NULL, NULL, NOW() - INTERVAL '30 hour', NOW()),
  ('REQ-20260311-0003', 'UPS', 'CUST102', 'FD-MISS-0077', 'TX-CN-7777', 'PLAN-E-100', 'Unknown Office, TX', FALSE, 404, 'Feeder or transformer connection not found in inventory.', 'TEAM1', 404, NULL, NULL, NULL, NULL, NULL, NOW() - INTERVAL '8 hour', NOW())
ON CONFLICT (request_id) DO UPDATE SET
  customer_name = EXCLUDED.customer_name,
  customer_id = EXCLUDED.customer_id,
  feeder_id = EXCLUDED.feeder_id,
  transformer_connection_id = EXCLUDED.transformer_connection_id,
  plan_id = EXCLUDED.plan_id,
  address_location = EXCLUDED.address_location,
  signed_disconnect_document = EXCLUDED.signed_disconnect_document,
  status = EXCLUDED.status,
  status_reason = EXCLUDED.status_reason,
  assigned_team = EXCLUDED.assigned_team,
  sync_status_code = EXCLUDED.sync_status_code,
  async_status_code = EXCLUDED.async_status_code,
  soft_disconnect_at = EXCLUDED.soft_disconnect_at,
  hard_disconnect_due_at = EXCLUDED.hard_disconnect_due_at,
  hard_disconnect_at = EXCLUDED.hard_disconnect_at,
  feeder_closed_at = EXCLUDED.feeder_closed_at,
  updated_at = EXCLUDED.updated_at;

INSERT INTO zp_inventory_data (
  request_id,
  feeder_id,
  transformer_connection_id,
  customer_id,
  customer_name,
  plan_id,
  address_location,
  feeder_state,
  feeder_closed_date,
  last_verified_at,
  created_at,
  updated_at
) VALUES
  ('REQ-20260311-0001', 'FD-IRV-1022', 'TX-CN-0091', 'CUST102', 'UPS', 'PLAN-E-100', 'Irving Office, TX', 'ACTIVE', NULL, NOW() - INTERVAL '3 hour', NOW() - INTERVAL '20 day', NOW()),
  ('REQ-20260311-0002', 'FD-COP-2210', 'TX-CN-0033', 'CUST102', 'UPS', 'PLAN-E-100', 'Coppell Office, TX', 'ACTIVE', NULL, NOW() - INTERVAL '3 hour', NOW() - INTERVAL '18 day', NOW()),
  ('REQ-20260311-0003', 'FD-MISS-0077', 'TX-CN-7777', 'CUST102', 'UPS', 'PLAN-E-100', 'Unknown Office, TX', 'MISSING', NULL, NOW() - INTERVAL '3 hour', NOW() - INTERVAL '18 day', NOW())
ON CONFLICT (request_id) DO UPDATE SET
  feeder_id = EXCLUDED.feeder_id,
  transformer_connection_id = EXCLUDED.transformer_connection_id,
  customer_id = EXCLUDED.customer_id,
  customer_name = EXCLUDED.customer_name,
  plan_id = EXCLUDED.plan_id,
  address_location = EXCLUDED.address_location,
  feeder_state = EXCLUDED.feeder_state,
  feeder_closed_date = EXCLUDED.feeder_closed_date,
  last_verified_at = EXCLUDED.last_verified_at,
  updated_at = EXCLUDED.updated_at;

INSERT INTO zp_disco_trans_data (
  request_id,
  action_id,
  status,
  disconnect_order_no,
  logged_user_id,
  notes_text,
  due_date,
  created_at
) VALUES
  ('REQ-20260311-0001', 1000, 710, NULL, 'TEAM1_USER_01', 'Team1 assigned to self and started validation.', CURRENT_DATE + 1, NOW() - INTERVAL '8 hour'),
  ('REQ-20260311-0001', 2000, 800, NULL, 'TEAM1_USER_01', 'Moved to Team2 queue.', CURRENT_DATE + 1, NOW() - INTERVAL '6 hour'),
  ('REQ-20260311-0002', 4000, 810, NULL, 'TEAM2_USER_08', 'Team2 assigned to self for final checks.', CURRENT_DATE, NOW() - INTERVAL '28 hour'),
  ('REQ-20260311-0002', 7000, 835, 'DO-884401', 'TEAM2_USER_08', 'Approved and submitted to downstream.', CURRENT_DATE, NOW() - INTERVAL '27 hour')
ON CONFLICT DO NOTHING;
