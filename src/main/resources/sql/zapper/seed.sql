-- Zapper demo transaction seed (Postgres)
-- Purpose:
--   Realistic sample rows for live DBKG testing.
--   The data intentionally includes:
--   - an ASSIGNED -> REJECTED transition in the last 24h
--   - an inventory-missing scenario
--   - a submitted disconnect stuck >24h with no zp_disconnect_id
--   - an order-side disconnect id missing in BillBank

INSERT INTO zp_customer (customer_id, customer_name, customer_type, contact_number, email_address, customer_status, created_at, updated_at)
VALUES
  ('CUST100', 'UPS', 'ENTERPRISE', '+1-972-555-0100', 'ops@ups.example.com', 'ACTIVE', NOW() - INTERVAL '400 days', NOW()),
  ('CUST200', 'Acme Manufacturing', 'ENTERPRISE', '+1-214-555-0200', 'grid@acme.example.com', 'ACTIVE', NOW() - INTERVAL '300 days', NOW()),
  ('CUST300', 'Northwind Retail', 'MID_MARKET', '+1-469-555-0300', 'energy@northwind.example.com', 'ACTIVE', NOW() - INTERVAL '250 days', NOW())
ON CONFLICT (customer_id) DO UPDATE
SET
  customer_name = EXCLUDED.customer_name,
  customer_type = EXCLUDED.customer_type,
  contact_number = EXCLUDED.contact_number,
  email_address = EXCLUDED.email_address,
  customer_status = EXCLUDED.customer_status,
  updated_at = EXCLUDED.updated_at;

INSERT INTO zp_account (account_id, customer_id, provider_code, bill_plan_code, current_billing_cost, account_status, created_at, updated_at)
VALUES
  ('UPSA100', 'CUST100', 'ZAPPER', 'BIZ-FLEX', 12850.75, 'ACTIVE', NOW() - INTERVAL '400 days', NOW()),
  ('ACMA200', 'CUST200', 'ZAPPER', 'IND-GRID', 9830.20, 'ACTIVE', NOW() - INTERVAL '300 days', NOW()),
  ('NWRA300', 'CUST300', 'ZAPPER', 'SMB-SAVER', 4210.15, 'ACTIVE', NOW() - INTERVAL '250 days', NOW())
ON CONFLICT (account_id) DO UPDATE
SET
  customer_id = EXCLUDED.customer_id,
  provider_code = EXCLUDED.provider_code,
  bill_plan_code = EXCLUDED.bill_plan_code,
  current_billing_cost = EXCLUDED.current_billing_cost,
  account_status = EXCLUDED.account_status,
  updated_at = EXCLUDED.updated_at;

INSERT INTO zp_location (location_id, location_name, city_name, state_code, zip_code, service_address, created_at, updated_at)
VALUES
  ('LOC100', 'UPS Irving Hub', 'Irving', 'TX', '75061', '101 Distribution Way, Irving, TX 75061', NOW() - INTERVAL '400 days', NOW()),
  ('LOC101', 'UPS Dallas Hub', 'Dallas', 'TX', '75201', '500 Commerce St, Dallas, TX 75201', NOW() - INTERVAL '390 days', NOW()),
  ('LOC200', 'Acme Austin Plant', 'Austin', 'TX', '73301', '88 Industrial Park Rd, Austin, TX 73301', NOW() - INTERVAL '300 days', NOW()),
  ('LOC300', 'Northwind Plano Store', 'Plano', 'TX', '75024', '300 Retail Plaza, Plano, TX 75024', NOW() - INTERVAL '250 days', NOW())
ON CONFLICT (location_id) DO UPDATE
SET
  location_name = EXCLUDED.location_name,
  city_name = EXCLUDED.city_name,
  state_code = EXCLUDED.state_code,
  zip_code = EXCLUDED.zip_code,
  service_address = EXCLUDED.service_address,
  updated_at = EXCLUDED.updated_at;

INSERT INTO zp_account_location (acc_loc_id, account_id, customer_id, location_id, customer_loc_id, service_status, created_at, updated_at)
VALUES
  ('ALOC100', 'UPSA100', 'CUST100', 'LOC100', 'CULOC100', 'ACTIVE', NOW() - INTERVAL '400 days', NOW()),
  ('ALOC101', 'UPSA100', 'CUST100', 'LOC101', 'CULOC101', 'ACTIVE', NOW() - INTERVAL '390 days', NOW()),
  ('ALOC200', 'ACMA200', 'CUST200', 'LOC200', 'CULOC200', 'ACTIVE', NOW() - INTERVAL '300 days', NOW()),
  ('ALOC300', 'NWRA300', 'CUST300', 'LOC300', 'CULOC300', 'ACTIVE', NOW() - INTERVAL '250 days', NOW())
ON CONFLICT (acc_loc_id) DO UPDATE
SET
  account_id = EXCLUDED.account_id,
  customer_id = EXCLUDED.customer_id,
  location_id = EXCLUDED.location_id,
  customer_loc_id = EXCLUDED.customer_loc_id,
  service_status = EXCLUDED.service_status,
  updated_at = EXCLUDED.updated_at;

INSERT INTO zp_connection (zp_connection_id, account_id, customer_id, location_id, acc_loc_id, customer_loc_id, zapper_id, connection_order_number, connection_status, connected_at, created_at, updated_at)
VALUES
  ('ZPCON100', 'UPSA100', 'CUST100', 'LOC100', 'ALOC100', 'CULOC100', 'ZPLOC9001', 'CONN-10001', 'ACTIVE', NOW() - INTERVAL '365 days', NOW() - INTERVAL '365 days', NOW()),
  ('ZPCON101', 'UPSA100', 'CUST100', 'LOC101', 'ALOC101', 'CULOC101', 'ZPLOC9002', 'CONN-10002', 'ACTIVE', NOW() - INTERVAL '340 days', NOW() - INTERVAL '340 days', NOW()),
  ('ZPCON200', 'ACMA200', 'CUST200', 'LOC200', 'ALOC200', 'CULOC200', 'ZPLOC9101', 'CONN-20001', 'ACTIVE', NOW() - INTERVAL '280 days', NOW() - INTERVAL '280 days', NOW()),
  ('ZPCON300', 'NWRA300', 'CUST300', 'LOC300', 'ALOC300', 'CULOC300', 'ZPLOC9201', 'CONN-30001', 'ACTIVE', NOW() - INTERVAL '220 days', NOW() - INTERVAL '220 days', NOW())
ON CONFLICT (zp_connection_id) DO UPDATE
SET
  account_id = EXCLUDED.account_id,
  customer_id = EXCLUDED.customer_id,
  location_id = EXCLUDED.location_id,
  acc_loc_id = EXCLUDED.acc_loc_id,
  customer_loc_id = EXCLUDED.customer_loc_id,
  zapper_id = EXCLUDED.zapper_id,
  connection_order_number = EXCLUDED.connection_order_number,
  connection_status = EXCLUDED.connection_status,
  connected_at = EXCLUDED.connected_at,
  updated_at = EXCLUDED.updated_at;

INSERT INTO zp_inventory_service (inventory_id, account_id, customer_id, acc_loc_id, zp_connection_id, zapper_id, inventory_status, provisioned_flag, inventory_sync_status, last_verified_at, created_at, updated_at)
VALUES
  ('INV100', 'UPSA100', 'CUST100', 'ALOC100', 'ZPCON100', 'ZPLOC9001', 'ACTIVE', TRUE, 'SYNCED', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '365 days', NOW()),
  ('INV101', 'UPSA100', 'CUST100', 'ALOC101', 'ZPCON101', 'ZPLOC9002', 'ACTIVE', TRUE, 'SYNCED', NOW() - INTERVAL '3 hours', NOW() - INTERVAL '340 days', NOW()),
  ('INV300', 'NWRA300', 'CUST300', 'ALOC300', 'ZPCON300', 'ZPLOC9201', 'ACTIVE', TRUE, 'SYNCED', NOW() - INTERVAL '4 hours', NOW() - INTERVAL '220 days', NOW())
ON CONFLICT (inventory_id) DO UPDATE
SET
  account_id = EXCLUDED.account_id,
  customer_id = EXCLUDED.customer_id,
  acc_loc_id = EXCLUDED.acc_loc_id,
  zp_connection_id = EXCLUDED.zp_connection_id,
  zapper_id = EXCLUDED.zapper_id,
  inventory_status = EXCLUDED.inventory_status,
  provisioned_flag = EXCLUDED.provisioned_flag,
  inventory_sync_status = EXCLUDED.inventory_sync_status,
  last_verified_at = EXCLUDED.last_verified_at,
  updated_at = EXCLUDED.updated_at;

INSERT INTO zp_request (zp_request_id, zp_connection_id, account_id, customer_id, location_id, acc_loc_id, zp_customer_name, zp_cust_zip, zp_contact_number, request_type, requested_provider, request_status, requested_at, submitted_to_aso_at, created_at, updated_at)
VALUES
  ('ZPR1001', 'ZPCON100', 'UPSA100', 'CUST100', 'LOC100', 'ALOC100', 'UPS', '75061', '+1-972-555-0100', 'DISCONNECT', 'GEXXA', 'REJECTED', NOW() - INTERVAL '12 hours', NULL, NOW() - INTERVAL '12 hours', NOW()),
  ('ZPR1002', 'ZPCON200', 'ACMA200', 'CUST200', 'LOC200', 'ALOC200', 'Acme Manufacturing', '73301', '+1-214-555-0200', 'DISCONNECT', 'GEXXA', 'INVENTORY_ERROR', NOW() - INTERVAL '7 hours', NULL, NOW() - INTERVAL '7 hours', NOW()),
  ('ZPR1003', 'ZPCON101', 'UPSA100', 'CUST100', 'LOC101', 'ALOC101', 'UPS', '75201', '+1-972-555-0100', 'DISCONNECT', 'GEXXA', 'SUBMITTED', NOW() - INTERVAL '40 hours', NOW() - INTERVAL '30 hours', NOW() - INTERVAL '40 hours', NOW()),
  ('ZPR1004', 'ZPCON300', 'NWRA300', 'CUST300', 'LOC300', 'ALOC300', 'Northwind Retail', '75024', '+1-469-555-0300', 'DISCONNECT', 'GEXXA', 'SUBMITTED', NOW() - INTERVAL '32 hours', NOW() - INTERVAL '26 hours', NOW() - INTERVAL '32 hours', NOW()),
  ('ZPR1005', 'ZPCON100', 'UPSA100', 'CUST100', 'LOC100', 'ALOC100', 'UPS', '75061', '+1-972-555-0100', 'DISCONNECT', 'GEXXA', 'COMPLETED', NOW() - INTERVAL '72 hours', NOW() - INTERVAL '68 hours', NOW() - INTERVAL '72 hours', NOW())
ON CONFLICT (zp_request_id) DO UPDATE
SET
  zp_connection_id = EXCLUDED.zp_connection_id,
  account_id = EXCLUDED.account_id,
  customer_id = EXCLUDED.customer_id,
  location_id = EXCLUDED.location_id,
  acc_loc_id = EXCLUDED.acc_loc_id,
  zp_customer_name = EXCLUDED.zp_customer_name,
  zp_cust_zip = EXCLUDED.zp_cust_zip,
  zp_contact_number = EXCLUDED.zp_contact_number,
  request_type = EXCLUDED.request_type,
  requested_provider = EXCLUDED.requested_provider,
  request_status = EXCLUDED.request_status,
  requested_at = EXCLUDED.requested_at,
  submitted_to_aso_at = EXCLUDED.submitted_to_aso_at,
  updated_at = EXCLUDED.updated_at;

INSERT INTO zp_location_validation (validation_id, location_id, zp_request_id, validated_zip_code, zip_match_flag, validation_status, validated_at)
VALUES
  ('VAL1001', 'LOC100', 'ZPR1001', '75061', TRUE, 'VALID', NOW() - INTERVAL '11 hours'),
  ('VAL1002', 'LOC200', 'ZPR1002', '73301', TRUE, 'VALID', NOW() - INTERVAL '6 hours'),
  ('VAL1003', 'LOC101', 'ZPR1003', '75201', TRUE, 'VALID', NOW() - INTERVAL '39 hours'),
  ('VAL1004', 'LOC300', 'ZPR1004', '75024', TRUE, 'VALID', NOW() - INTERVAL '31 hours')
ON CONFLICT (validation_id) DO UPDATE
SET
  location_id = EXCLUDED.location_id,
  zp_request_id = EXCLUDED.zp_request_id,
  validated_zip_code = EXCLUDED.validated_zip_code,
  zip_match_flag = EXCLUDED.zip_match_flag,
  validation_status = EXCLUDED.validation_status,
  validated_at = EXCLUDED.validated_at;

INSERT INTO zp_ui_data (zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, zp_queue_code, last_updated_at)
VALUES
  ('ZPR1001', 'MCASR_001', 'Assigned, reviewed, then rejected because the service disconnect request failed internal validation.', '400', 'ASR_REJECTED', NOW() - INTERVAL '9 hours'),
  ('ZPR1002', 'NMCASR_009', 'Inventory lookup failed in UI while checking account-location inventory.', '200', 'ASR_ASSIGNED', NOW() - INTERVAL '5 hours'),
  ('ZPR1003', 'MCASO_002', 'Submitted to order service; waiting for disconnect id beyond SLA.', '500', 'ASO_SUBMITTED', NOW() - INTERVAL '30 hours'),
  ('ZPR1004', 'NMCASO_004', 'Submitted successfully; downstream says complete but billing is still missing the disconnect record.', '500', 'ASO_SUBMITTED', NOW() - INTERVAL '26 hours'),
  ('ZPR1005', 'MCASO_007', 'Disconnect completed and billing record updated.', '500', 'ASO_COMPLETED', NOW() - INTERVAL '66 hours')
ON CONFLICT (zp_request_id) DO UPDATE
SET
  zp_asr_team_member_id = EXCLUDED.zp_asr_team_member_id,
  zp_asr_team_notes = EXCLUDED.zp_asr_team_notes,
  zp_action_id = EXCLUDED.zp_action_id,
  zp_queue_code = EXCLUDED.zp_queue_code,
  last_updated_at = EXCLUDED.last_updated_at;

INSERT INTO zp_ui_data_history (history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES
  ('H1001', 'ZPR1001', 'MCASR_001', 'Request opened for review.', '100', 'MCASR_001', NOW() - INTERVAL '12 hours'),
  ('H1002', 'ZPR1001', 'MCASR_001', 'Ownership assigned for review.', '200', 'MCASR_001', NOW() - INTERVAL '11 hours'),
  ('H1003', 'ZPR1001', 'MCASR_001', 'Rejected after review.', '400', 'MCASR_001', NOW() - INTERVAL '9 hours'),
  ('H1004', 'ZPR1002', 'NMCASR_009', 'Request opened for review.', '100', 'NMCASR_009', NOW() - INTERVAL '7 hours'),
  ('H1005', 'ZPR1002', 'NMCASR_009', 'Assigned for inventory validation.', '200', 'NMCASR_009', NOW() - INTERVAL '5 hours'),
  ('H1006', 'ZPR1003', 'MCASR_003', 'Request opened for review.', '100', 'MCASR_003', NOW() - INTERVAL '40 hours'),
  ('H1007', 'ZPR1003', 'MCASR_003', 'Assigned for review.', '200', 'MCASR_003', NOW() - INTERVAL '38 hours'),
  ('H1008', 'ZPR1003', 'MCASR_003', 'Signed off and handed to ASO.', '300', 'MCASR_003', NOW() - INTERVAL '34 hours'),
  ('H1009', 'ZPR1003', 'MCASO_002', 'Submitted downstream.', '500', 'MCASO_002', NOW() - INTERVAL '30 hours'),
  ('H1010', 'ZPR1004', 'NMCASR_005', 'Request opened for review.', '100', 'NMCASR_005', NOW() - INTERVAL '32 hours'),
  ('H1011', 'ZPR1004', 'NMCASR_005', 'Assigned for review.', '200', 'NMCASR_005', NOW() - INTERVAL '31 hours'),
  ('H1012', 'ZPR1004', 'NMCASR_005', 'Signed off and handed to ASO.', '300', 'NMCASR_005', NOW() - INTERVAL '29 hours'),
  ('H1013', 'ZPR1004', 'NMCASO_004', 'Submitted downstream.', '500', 'NMCASO_004', NOW() - INTERVAL '26 hours'),
  ('H1014', 'ZPR1005', 'MCASR_007', 'Request opened for review.', '100', 'MCASR_007', NOW() - INTERVAL '72 hours'),
  ('H1015', 'ZPR1005', 'MCASR_007', 'Assigned for review.', '200', 'MCASR_007', NOW() - INTERVAL '71 hours'),
  ('H1016', 'ZPR1005', 'MCASR_007', 'Signed off and handed to ASO.', '300', 'MCASR_007', NOW() - INTERVAL '69 hours'),
  ('H1017', 'ZPR1005', 'MCASO_007', 'Submitted downstream.', '500', 'MCASO_007', NOW() - INTERVAL '68 hours')
ON CONFLICT (history_id) DO UPDATE
SET
  zp_request_id = EXCLUDED.zp_request_id,
  zp_asr_team_member_id = EXCLUDED.zp_asr_team_member_id,
  zp_asr_team_notes = EXCLUDED.zp_asr_team_notes,
  zp_action_id = EXCLUDED.zp_action_id,
  changed_by = EXCLUDED.changed_by,
  created_date = EXCLUDED.created_date;

INSERT INTO zp_disconnect_order (don, zp_request_id, zp_connection_id, account_id, acc_loc_id, zapper_id, submit_status, submit_channel, submitted_at, downstream_status, zp_disconnect_id, disconnect_due_at, last_checked_at, created_at, updated_at)
VALUES
  ('DON9001', 'ZPR1003', 'ZPCON101', 'UPSA100', 'ALOC101', 'ZPLOC9002', 'SUBMITTED', 'ZAPPER_CENTRAL', NOW() - INTERVAL '30 hours', 'FAILED_TERMINATION_FEE', NULL, NOW() - INTERVAL '6 hours', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '30 hours', NOW()),
  ('DON9002', 'ZPR1004', 'ZPCON300', 'NWRA300', 'ALOC300', 'ZPLOC9201', 'SUBMITTED', 'ZAPPER_CENTRAL', NOW() - INTERVAL '26 hours', 'COMPLETED', 'ZPDISC7002', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hours', NOW() - INTERVAL '26 hours', NOW()),
  ('DON9003', 'ZPR1005', 'ZPCON100', 'UPSA100', 'ALOC100', 'ZPLOC9001', 'SUBMITTED', 'ZAPPER_CENTRAL', NOW() - INTERVAL '68 hours', 'COMPLETED', 'ZPDISC7003', NOW() - INTERVAL '40 hours', NOW() - INTERVAL '39 hours', NOW() - INTERVAL '68 hours', NOW())
ON CONFLICT (don) DO UPDATE
SET
  zp_request_id = EXCLUDED.zp_request_id,
  zp_connection_id = EXCLUDED.zp_connection_id,
  account_id = EXCLUDED.account_id,
  acc_loc_id = EXCLUDED.acc_loc_id,
  zapper_id = EXCLUDED.zapper_id,
  submit_status = EXCLUDED.submit_status,
  submit_channel = EXCLUDED.submit_channel,
  submitted_at = EXCLUDED.submitted_at,
  downstream_status = EXCLUDED.downstream_status,
  zp_disconnect_id = EXCLUDED.zp_disconnect_id,
  disconnect_due_at = EXCLUDED.disconnect_due_at,
  last_checked_at = EXCLUDED.last_checked_at,
  updated_at = EXCLUDED.updated_at;

INSERT INTO zp_order_downstream_check (check_id, don, check_code, check_label, check_status, failure_reason, checked_at)
VALUES
  ('CHK90011', 'DON9001', 'ORDER_VALIDATION', 'Order disconnect eligibility', 'PASS', NULL, NOW() - INTERVAL '29 hours'),
  ('CHK90012', 'DON9001', 'BILL_CEASE', 'Bill cease readiness', 'PASS', NULL, NOW() - INTERVAL '28 hours'),
  ('CHK90013', 'DON9001', 'TERMINATION_FEE', 'Termination fee validation', 'FAIL', 'Termination fee still pending clearance.', NOW() - INTERVAL '2 hours'),
  ('CHK90021', 'DON9002', 'ORDER_VALIDATION', 'Order disconnect eligibility', 'PASS', NULL, NOW() - INTERVAL '25 hours'),
  ('CHK90022', 'DON9002', 'BILL_CEASE', 'Bill cease readiness', 'PASS', NULL, NOW() - INTERVAL '24 hours'),
  ('CHK90023', 'DON9002', 'TERMINATION_FEE', 'Termination fee validation', 'PASS', NULL, NOW() - INTERVAL '23 hours'),
  ('CHK90031', 'DON9003', 'ORDER_VALIDATION', 'Order disconnect eligibility', 'PASS', NULL, NOW() - INTERVAL '67 hours'),
  ('CHK90032', 'DON9003', 'BILL_CEASE', 'Bill cease readiness', 'PASS', NULL, NOW() - INTERVAL '66 hours'),
  ('CHK90033', 'DON9003', 'TERMINATION_FEE', 'Termination fee validation', 'PASS', NULL, NOW() - INTERVAL '65 hours')
ON CONFLICT (check_id) DO UPDATE
SET
  don = EXCLUDED.don,
  check_code = EXCLUDED.check_code,
  check_label = EXCLUDED.check_label,
  check_status = EXCLUDED.check_status,
  failure_reason = EXCLUDED.failure_reason,
  checked_at = EXCLUDED.checked_at;

INSERT INTO zp_billbank_record (billbank_id, account_id, zp_connection_id, zapper_id, zp_disconnect_id, bill_plan_code, current_billing_cost, bill_cease_status, termination_fee_amount, overdue_amount, pending_bill_amount, record_status, updated_at)
VALUES
  ('BB1001', 'UPSA100', 'ZPCON100', 'ZPLOC9001', 'ZPDISC7003', 'BIZ-FLEX', 0.00, 'CEASED', 0.00, 0.00, 0.00, 'CLOSED', NOW() - INTERVAL '38 hours'),
  ('BB1002', 'UPSA100', 'ZPCON101', 'ZPLOC9002', NULL, 'BIZ-FLEX', 12850.75, 'ACTIVE', 250.00, 0.00, 12850.75, 'OPEN', NOW() - INTERVAL '2 hours')
ON CONFLICT (billbank_id) DO UPDATE
SET
  account_id = EXCLUDED.account_id,
  zp_connection_id = EXCLUDED.zp_connection_id,
  zapper_id = EXCLUDED.zapper_id,
  zp_disconnect_id = EXCLUDED.zp_disconnect_id,
  bill_plan_code = EXCLUDED.bill_plan_code,
  current_billing_cost = EXCLUDED.current_billing_cost,
  bill_cease_status = EXCLUDED.bill_cease_status,
  termination_fee_amount = EXCLUDED.termination_fee_amount,
  overdue_amount = EXCLUDED.overdue_amount,
  pending_bill_amount = EXCLUDED.pending_bill_amount,
  record_status = EXCLUDED.record_status,
  updated_at = EXCLUDED.updated_at;

INSERT INTO zp_notification_event (notification_id, zp_request_id, don, notification_type, notification_status, recipient_email, sent_at, created_at)
VALUES
  ('NTF9001', 'ZPR1003', 'DON9001', 'ASO_SLA_BREACH', 'SENT', 'aso-alerts@zapper.example.com', NOW() - INTERVAL '1 hours', NOW() - INTERVAL '1 hours'),
  ('NTF9002', 'ZPR1004', 'DON9002', 'BILLBANK_SYNC_ALERT', 'PENDING', 'billing-alerts@zapper.example.com', NULL, NOW() - INTERVAL '30 minutes')
ON CONFLICT (notification_id) DO UPDATE
SET
  zp_request_id = EXCLUDED.zp_request_id,
  don = EXCLUDED.don,
  notification_type = EXCLUDED.notification_type,
  notification_status = EXCLUDED.notification_status,
  recipient_email = EXCLUDED.recipient_email,
  sent_at = EXCLUDED.sent_at,
  created_at = EXCLUDED.created_at;
