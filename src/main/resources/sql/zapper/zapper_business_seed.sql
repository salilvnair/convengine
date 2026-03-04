
INSERT INTO zp_account
(account_id, customer_id, provider_code, bill_plan_code, current_billing_cost, account_status, created_at, updated_at)
VALUES('UPSA100', 'CUST100', 'ZAPPER', 'BIZ-FLEX', 12850.75, 'ACTIVE', '2025-01-25 21:27:54.528', '2026-03-01 21:27:54.528');
INSERT INTO zp_account
(account_id, customer_id, provider_code, bill_plan_code, current_billing_cost, account_status, created_at, updated_at)
VALUES('ACMA200', 'CUST200', 'ZAPPER', 'IND-GRID', 9830.20, 'ACTIVE', '2025-05-05 21:27:54.528', '2026-03-01 21:27:54.528');
INSERT INTO zp_account
(account_id, customer_id, provider_code, bill_plan_code, current_billing_cost, account_status, created_at, updated_at)
VALUES('NWRA300', 'CUST300', 'ZAPPER', 'SMB-SAVER', 4210.15, 'ACTIVE', '2025-06-24 21:27:54.528', '2026-03-01 21:27:54.528');


INSERT INTO zp_account_location
(acc_loc_id, account_id, customer_id, location_id, customer_loc_id, service_status, created_at, updated_at)
VALUES('ALOC100', 'UPSA100', 'CUST100', 'LOC100', 'CULOC100', 'ACTIVE', '2025-01-25 21:27:54.538', '2026-03-01 21:27:54.538');
INSERT INTO zp_account_location
(acc_loc_id, account_id, customer_id, location_id, customer_loc_id, service_status, created_at, updated_at)
VALUES('ALOC101', 'UPSA100', 'CUST100', 'LOC101', 'CULOC101', 'ACTIVE', '2025-02-04 21:27:54.538', '2026-03-01 21:27:54.538');
INSERT INTO zp_account_location
(acc_loc_id, account_id, customer_id, location_id, customer_loc_id, service_status, created_at, updated_at)
VALUES('ALOC200', 'ACMA200', 'CUST200', 'LOC200', 'CULOC200', 'ACTIVE', '2025-05-05 21:27:54.538', '2026-03-01 21:27:54.538');
INSERT INTO zp_account_location
(acc_loc_id, account_id, customer_id, location_id, customer_loc_id, service_status, created_at, updated_at)
VALUES('ALOC300', 'NWRA300', 'CUST300', 'LOC300', 'CULOC300', 'ACTIVE', '2025-06-24 21:27:54.538', '2026-03-01 21:27:54.538');


INSERT INTO zp_billbank_record
(billbank_id, account_id, zp_connection_id, zapper_id, zp_disconnect_id, bill_plan_code, current_billing_cost, bill_cease_status, termination_fee_amount, overdue_amount, pending_bill_amount, record_status, updated_at)
VALUES('BB1001', 'UPSA100', 'ZPCON100', 'ZPLOC9001', 'ZPDISC7003', 'BIZ-FLEX', 0.00, 'CEASED', 0.00, 0.00, 0.00, 'CLOSED', '2026-02-28 07:27:54.592');
INSERT INTO zp_billbank_record
(billbank_id, account_id, zp_connection_id, zapper_id, zp_disconnect_id, bill_plan_code, current_billing_cost, bill_cease_status, termination_fee_amount, overdue_amount, pending_bill_amount, record_status, updated_at)
VALUES('BB1002', 'UPSA100', 'ZPCON101', 'ZPLOC9002', NULL, 'BIZ-FLEX', 12850.75, 'ACTIVE', 250.00, 0.00, 12850.75, 'OPEN', '2026-03-01 19:27:54.592');


INSERT INTO zp_connection
(zp_connection_id, account_id, customer_id, location_id, acc_loc_id, customer_loc_id, zapper_id, connection_order_number, connection_status, connected_at, created_at, updated_at)
VALUES('ZPCON100', 'UPSA100', 'CUST100', 'LOC100', 'ALOC100', 'CULOC100', 'ZPLOC9001', 'CONN-10001', 'ACTIVE', '2025-03-01 21:27:54.547', '2025-03-01 21:27:54.547', '2026-03-01 21:27:54.547');
INSERT INTO zp_connection
(zp_connection_id, account_id, customer_id, location_id, acc_loc_id, customer_loc_id, zapper_id, connection_order_number, connection_status, connected_at, created_at, updated_at)
VALUES('ZPCON101', 'UPSA100', 'CUST100', 'LOC101', 'ALOC101', 'CULOC101', 'ZPLOC9002', 'CONN-10002', 'ACTIVE', '2025-03-26 21:27:54.547', '2025-03-26 21:27:54.547', '2026-03-01 21:27:54.547');
INSERT INTO zp_connection
(zp_connection_id, account_id, customer_id, location_id, acc_loc_id, customer_loc_id, zapper_id, connection_order_number, connection_status, connected_at, created_at, updated_at)
VALUES('ZPCON200', 'ACMA200', 'CUST200', 'LOC200', 'ALOC200', 'CULOC200', 'ZPLOC9101', 'CONN-20001', 'ACTIVE', '2025-05-25 21:27:54.547', '2025-05-25 21:27:54.547', '2026-03-01 21:27:54.547');
INSERT INTO zp_connection
(zp_connection_id, account_id, customer_id, location_id, acc_loc_id, customer_loc_id, zapper_id, connection_order_number, connection_status, connected_at, created_at, updated_at)
VALUES('ZPCON300', 'NWRA300', 'CUST300', 'LOC300', 'ALOC300', 'CULOC300', 'ZPLOC9201', 'CONN-30001', 'ACTIVE', '2025-07-24 21:27:54.547', '2025-07-24 21:27:54.547', '2026-03-01 21:27:54.547');




INSERT INTO zp_customer
(customer_id, customer_name, customer_type, contact_number, email_address, customer_status, created_at, updated_at)
VALUES('CUST100', 'UPS', 'ENTERPRISE', '+1-972-555-0100', 'ops@ups.example.com', 'ACTIVE', '2025-01-25 21:27:54.524', '2026-03-01 21:27:54.524');
INSERT INTO zp_customer
(customer_id, customer_name, customer_type, contact_number, email_address, customer_status, created_at, updated_at)
VALUES('CUST200', 'Acme Manufacturing', 'ENTERPRISE', '+1-214-555-0200', 'grid@acme.example.com', 'ACTIVE', '2025-05-05 21:27:54.524', '2026-03-01 21:27:54.524');
INSERT INTO zp_customer
(customer_id, customer_name, customer_type, contact_number, email_address, customer_status, created_at, updated_at)
VALUES('CUST300', 'Northwind Retail', 'MID_MARKET', '+1-469-555-0300', 'energy@northwind.example.com', 'ACTIVE', '2025-06-24 21:27:54.524', '2026-03-01 21:27:54.524');



INSERT INTO zp_disconnect_order
(don, zp_request_id, zp_connection_id, account_id, acc_loc_id, zapper_id, submit_status, submit_channel, submitted_at, downstream_status, zp_disconnect_id, disconnect_due_at, last_checked_at, created_at, updated_at)
VALUES('DON9001', 'ZPR1003', 'ZPCON101', 'UPSA100', 'ALOC101', 'ZPLOC9002', 'SUBMITTED', 'ZAPPER_CENTRAL', '2026-02-28 15:27:54.582', 'FAILED_TERMINATION_FEE', NULL, '2026-03-01 15:27:54.582', '2026-03-01 19:27:54.582', '2026-02-28 15:27:54.582', '2026-03-01 21:27:54.582');
INSERT INTO zp_disconnect_order
(don, zp_request_id, zp_connection_id, account_id, acc_loc_id, zapper_id, submit_status, submit_channel, submitted_at, downstream_status, zp_disconnect_id, disconnect_due_at, last_checked_at, created_at, updated_at)
VALUES('DON9002', 'ZPR1004', 'ZPCON300', 'NWRA300', 'ALOC300', 'ZPLOC9201', 'SUBMITTED', 'ZAPPER_CENTRAL', '2026-02-28 19:27:54.582', 'COMPLETED', 'ZPDISC7002', '2026-03-01 19:27:54.582', '2026-03-01 20:27:54.582', '2026-02-28 19:27:54.582', '2026-03-01 21:27:54.582');
INSERT INTO zp_disconnect_order
(don, zp_request_id, zp_connection_id, account_id, acc_loc_id, zapper_id, submit_status, submit_channel, submitted_at, downstream_status, zp_disconnect_id, disconnect_due_at, last_checked_at, created_at, updated_at)
VALUES('DON9003', 'ZPR1005', 'ZPCON100', 'UPSA100', 'ALOC100', 'ZPLOC9001', 'SUBMITTED', 'ZAPPER_CENTRAL', '2026-02-27 01:27:54.582', 'COMPLETED', 'ZPDISC7003', '2026-02-28 05:27:54.582', '2026-02-28 06:27:54.582', '2026-02-27 01:27:54.582', '2026-03-01 21:27:54.582');




INSERT INTO zp_connection
(zp_connection_id, account_id, customer_id, location_id, acc_loc_id, customer_loc_id, zapper_id, connection_order_number, connection_status, connected_at, created_at, updated_at)
VALUES('ZPCON100', 'UPSA100', 'CUST100', 'LOC100', 'ALOC100', 'CULOC100', 'ZPLOC9001', 'CONN-10001', 'ACTIVE', '2025-03-01 21:27:54.547', '2025-03-01 21:27:54.547', '2026-03-01 21:27:54.547');


INSERT INTO zp_location
(location_id, location_name, city_name, state_code, zip_code, service_address, created_at, updated_at)
VALUES('LOC100', 'UPS Irving Hub', 'Irving', 'TX', '75061', '101 Distribution Way, Irving, TX 75061', '2025-01-25 21:27:54.534', '2026-03-01 21:27:54.534');
INSERT INTO zp_location
(location_id, location_name, city_name, state_code, zip_code, service_address, created_at, updated_at)
VALUES('LOC101', 'UPS Dallas Hub', 'Dallas', 'TX', '75201', '500 Commerce St, Dallas, TX 75201', '2025-02-04 21:27:54.534', '2026-03-01 21:27:54.534');
INSERT INTO zp_location
(location_id, location_name, city_name, state_code, zip_code, service_address, created_at, updated_at)
VALUES('LOC200', 'Acme Austin Plant', 'Austin', 'TX', '73301', '88 Industrial Park Rd, Austin, TX 73301', '2025-05-05 21:27:54.534', '2026-03-01 21:27:54.534');
INSERT INTO zp_location
(location_id, location_name, city_name, state_code, zip_code, service_address, created_at, updated_at)
VALUES('LOC300', 'Northwind Plano Store', 'Plano', 'TX', '75024', '300 Retail Plaza, Plano, TX 75024', '2025-06-24 21:27:54.534', '2026-03-01 21:27:54.534');



INSERT INTO zp_location_validation
(validation_id, location_id, zp_request_id, validated_zip_code, zip_match_flag, validation_status, validated_at)
VALUES('VAL1001', 'LOC100', 'ZPR1001', '75061', true, 'VALID', '2026-03-01 10:27:54.567');
INSERT INTO zp_location_validation
(validation_id, location_id, zp_request_id, validated_zip_code, zip_match_flag, validation_status, validated_at)
VALUES('VAL1002', 'LOC200', 'ZPR1002', '73301', true, 'VALID', '2026-03-01 15:27:54.567');
INSERT INTO zp_location_validation
(validation_id, location_id, zp_request_id, validated_zip_code, zip_match_flag, validation_status, validated_at)
VALUES('VAL1003', 'LOC101', 'ZPR1003', '75201', true, 'VALID', '2026-02-28 06:27:54.567');
INSERT INTO zp_location_validation
(validation_id, location_id, zp_request_id, validated_zip_code, zip_match_flag, validation_status, validated_at)
VALUES('VAL1004', 'LOC300', 'ZPR1004', '75024', true, 'VALID', '2026-02-28 14:27:54.567');


INSERT INTO zp_notification_event
(notification_id, zp_request_id, don, notification_type, notification_status, recipient_email, sent_at, created_at)
VALUES('NTF9001', 'ZPR1003', 'DON9001', 'ASO_SLA_BREACH', 'SENT', 'aso-alerts@zapper.example.com', '2026-03-01 20:27:54.597', '2026-03-01 20:27:54.597');
INSERT INTO zp_notification_event
(notification_id, zp_request_id, don, notification_type, notification_status, recipient_email, sent_at, created_at)
VALUES('NTF9002', 'ZPR1004', 'DON9002', 'BILLBANK_SYNC_ALERT', 'PENDING', 'billing-alerts@zapper.example.com', NULL, '2026-03-01 20:57:54.597');





INSERT INTO zp_order_downstream_check
(check_id, don, check_code, check_label, check_status, failure_reason, checked_at)
VALUES('CHK90011', 'DON9001', 'ORDER_VALIDATION', 'Order disconnect eligibility', 'PASS', NULL, '2026-02-28 16:27:54.588');
INSERT INTO zp_order_downstream_check
(check_id, don, check_code, check_label, check_status, failure_reason, checked_at)
VALUES('CHK90012', 'DON9001', 'BILL_CEASE', 'Bill cease readiness', 'PASS', NULL, '2026-02-28 17:27:54.588');
INSERT INTO zp_order_downstream_check
(check_id, don, check_code, check_label, check_status, failure_reason, checked_at)
VALUES('CHK90013', 'DON9001', 'TERMINATION_FEE', 'Termination fee validation', 'FAIL', 'Termination fee still pending clearance.', '2026-03-01 19:27:54.588');
INSERT INTO zp_order_downstream_check
(check_id, don, check_code, check_label, check_status, failure_reason, checked_at)
VALUES('CHK90021', 'DON9002', 'ORDER_VALIDATION', 'Order disconnect eligibility', 'PASS', NULL, '2026-02-28 20:27:54.588');
INSERT INTO zp_order_downstream_check
(check_id, don, check_code, check_label, check_status, failure_reason, checked_at)
VALUES('CHK90022', 'DON9002', 'BILL_CEASE', 'Bill cease readiness', 'PASS', NULL, '2026-02-28 21:27:54.588');
INSERT INTO zp_order_downstream_check
(check_id, don, check_code, check_label, check_status, failure_reason, checked_at)
VALUES('CHK90023', 'DON9002', 'TERMINATION_FEE', 'Termination fee validation', 'PASS', NULL, '2026-02-28 22:27:54.588');
INSERT INTO zp_order_downstream_check
(check_id, don, check_code, check_label, check_status, failure_reason, checked_at)
VALUES('CHK90031', 'DON9003', 'ORDER_VALIDATION', 'Order disconnect eligibility', 'PASS', NULL, '2026-02-27 02:27:54.588');
INSERT INTO zp_order_downstream_check
(check_id, don, check_code, check_label, check_status, failure_reason, checked_at)
VALUES('CHK90032', 'DON9003', 'BILL_CEASE', 'Bill cease readiness', 'PASS', NULL, '2026-02-27 03:27:54.588');
INSERT INTO zp_order_downstream_check
(check_id, don, check_code, check_label, check_status, failure_reason, checked_at)
VALUES('CHK90033', 'DON9003', 'TERMINATION_FEE', 'Termination fee validation', 'PASS', NULL, '2026-02-27 04:27:54.588');



INSERT INTO zp_request
(zp_request_id, zp_connection_id, account_id, customer_id, location_id, acc_loc_id, zp_customer_name, zp_cust_zip, zp_contact_number, request_type, requested_provider, request_status, requested_at, submitted_to_aso_at, created_at, updated_at)
VALUES('ZPR1001', 'ZPCON100', 'UPSA100', 'CUST100', 'LOC100', 'ALOC100', 'UPS', '75061', '+1-972-555-0100', 'DISCONNECT', 'GEXXA', 'REJECTED', '2026-03-01 09:27:54.562', NULL, '2026-03-01 09:27:54.562', '2026-03-01 21:27:54.562');
INSERT INTO zp_request
(zp_request_id, zp_connection_id, account_id, customer_id, location_id, acc_loc_id, zp_customer_name, zp_cust_zip, zp_contact_number, request_type, requested_provider, request_status, requested_at, submitted_to_aso_at, created_at, updated_at)
VALUES('ZPR1002', 'ZPCON200', 'ACMA200', 'CUST200', 'LOC200', 'ALOC200', 'Acme Manufacturing', '73301', '+1-214-555-0200', 'DISCONNECT', 'GEXXA', 'INVENTORY_ERROR', '2026-03-01 14:27:54.562', NULL, '2026-03-01 14:27:54.562', '2026-03-01 21:27:54.562');
INSERT INTO zp_request
(zp_request_id, zp_connection_id, account_id, customer_id, location_id, acc_loc_id, zp_customer_name, zp_cust_zip, zp_contact_number, request_type, requested_provider, request_status, requested_at, submitted_to_aso_at, created_at, updated_at)
VALUES('ZPR1003', 'ZPCON101', 'UPSA100', 'CUST100', 'LOC101', 'ALOC101', 'UPS', '75201', '+1-972-555-0100', 'DISCONNECT', 'GEXXA', 'SUBMITTED', '2026-02-28 05:27:54.562', '2026-02-28 15:27:54.562', '2026-02-28 05:27:54.562', '2026-03-01 21:27:54.562');
INSERT INTO zp_request
(zp_request_id, zp_connection_id, account_id, customer_id, location_id, acc_loc_id, zp_customer_name, zp_cust_zip, zp_contact_number, request_type, requested_provider, request_status, requested_at, submitted_to_aso_at, created_at, updated_at)
VALUES('ZPR1004', 'ZPCON300', 'NWRA300', 'CUST300', 'LOC300', 'ALOC300', 'Northwind Retail', '75024', '+1-469-555-0300', 'DISCONNECT', 'GEXXA', 'SUBMITTED', '2026-02-28 13:27:54.562', '2026-02-28 19:27:54.562', '2026-02-28 13:27:54.562', '2026-03-01 21:27:54.562');
INSERT INTO zp_request
(zp_request_id, zp_connection_id, account_id, customer_id, location_id, acc_loc_id, zp_customer_name, zp_cust_zip, zp_contact_number, request_type, requested_provider, request_status, requested_at, submitted_to_aso_at, created_at, updated_at)
VALUES('ZPR1005', 'ZPCON100', 'UPSA100', 'CUST100', 'LOC100', 'ALOC100', 'UPS', '75061', '+1-972-555-0100', 'DISCONNECT', 'GEXXA', 'COMPLETED', '2026-02-26 21:27:54.562', '2026-02-27 01:27:54.562', '2026-02-26 21:27:54.562', '2026-03-01 21:27:54.562');



INSERT INTO zp_ui_data
(zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, zp_queue_code, last_updated_at)
VALUES('ZPR1001', 'MCASR_001', 'Assigned, reviewed, then rejected because the service disconnect request failed internal validation.', '400', 'ASR_REJECTED', '2026-03-01 12:27:54.571');
INSERT INTO zp_ui_data
(zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, zp_queue_code, last_updated_at)
VALUES('ZPR1002', 'NMCASR_009', 'Inventory lookup failed in UI while checking account-location inventory.', '200', 'ASR_ASSIGNED', '2026-03-01 16:27:54.571');
INSERT INTO zp_ui_data
(zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, zp_queue_code, last_updated_at)
VALUES('ZPR1003', 'MCASO_002', 'Submitted to order service; waiting for disconnect id beyond SLA.', '500', 'ASO_SUBMITTED', '2026-02-28 15:27:54.571');
INSERT INTO zp_ui_data
(zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, zp_queue_code, last_updated_at)
VALUES('ZPR1004', 'NMCASO_004', 'Submitted successfully; downstream says complete but billing is still missing the disconnect record.', '500', 'ASO_SUBMITTED', '2026-02-28 19:27:54.571');
INSERT INTO zp_ui_data
(zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, zp_queue_code, last_updated_at)
VALUES('ZPR1005', 'MCASO_007', 'Disconnect completed and billing record updated.', '500', 'ASO_COMPLETED', '2026-02-27 03:27:54.571');



INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1001', 'ZPR1001', 'MCASR_001', 'Request opened for review.', '100', 'MCASR_001', '2026-03-01 09:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1002', 'ZPR1001', 'MCASR_001', 'Ownership assigned for review.', '200', 'MCASR_001', '2026-03-01 10:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1003', 'ZPR1001', 'MCASR_001', 'Rejected after review.', '400', 'MCASR_001', '2026-03-01 12:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1004', 'ZPR1002', 'NMCASR_009', 'Request opened for review.', '100', 'NMCASR_009', '2026-03-01 14:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1005', 'ZPR1002', 'NMCASR_009', 'Assigned for inventory validation.', '200', 'NMCASR_009', '2026-03-01 16:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1006', 'ZPR1003', 'MCASR_003', 'Request opened for review.', '100', 'MCASR_003', '2026-02-28 05:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1007', 'ZPR1003', 'MCASR_003', 'Assigned for review.', '200', 'MCASR_003', '2026-02-28 07:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1008', 'ZPR1003', 'MCASR_003', 'Signed off and handed to ASO.', '300', 'MCASR_003', '2026-02-28 11:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1009', 'ZPR1003', 'MCASO_002', 'Submitted downstream.', '500', 'MCASO_002', '2026-02-28 15:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1010', 'ZPR1004', 'NMCASR_005', 'Request opened for review.', '100', 'NMCASR_005', '2026-02-28 13:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1011', 'ZPR1004', 'NMCASR_005', 'Assigned for review.', '200', 'NMCASR_005', '2026-02-28 14:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1012', 'ZPR1004', 'NMCASR_005', 'Signed off and handed to ASO.', '300', 'NMCASR_005', '2026-02-28 16:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1013', 'ZPR1004', 'NMCASO_004', 'Submitted downstream.', '500', 'NMCASO_004', '2026-02-28 19:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1014', 'ZPR1005', 'MCASR_007', 'Request opened for review.', '100', 'MCASR_007', '2026-02-26 21:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1015', 'ZPR1005', 'MCASR_007', 'Assigned for review.', '200', 'MCASR_007', '2026-02-26 22:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1016', 'ZPR1005', 'MCASR_007', 'Signed off and handed to ASO.', '300', 'MCASR_007', '2026-02-27 00:27:54.577');
INSERT INTO zp_ui_data_history
(history_id, zp_request_id, zp_asr_team_member_id, zp_asr_team_notes, zp_action_id, changed_by, created_date)
VALUES('H1017', 'ZPR1005', 'MCASO_007', 'Submitted downstream.', '500', 'MCASO_007', '2026-02-27 01:27:54.577');