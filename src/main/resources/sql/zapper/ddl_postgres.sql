-- Zapper demo transaction schema (Postgres)
-- Purpose:
--   Concrete transaction tables for live DBKG testing against the Zapper story.
--   This is consumer-domain sample data, separate from ConvEngine metadata tables.

CREATE TABLE IF NOT EXISTS zp_customer (
  customer_id VARCHAR(50) PRIMARY KEY,
  customer_name VARCHAR(150) NOT NULL,
  customer_type VARCHAR(50) NOT NULL,
  contact_number VARCHAR(30),
  email_address VARCHAR(200),
  customer_status VARCHAR(30) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS zp_account (
  account_id VARCHAR(50) PRIMARY KEY,
  customer_id VARCHAR(50) NOT NULL REFERENCES zp_customer(customer_id),
  provider_code VARCHAR(50) NOT NULL,
  bill_plan_code VARCHAR(50) NOT NULL,
  current_billing_cost NUMERIC(12,2) NOT NULL DEFAULT 0,
  account_status VARCHAR(30) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS zp_location (
  location_id VARCHAR(50) PRIMARY KEY,
  location_name VARCHAR(150) NOT NULL,
  city_name VARCHAR(100) NOT NULL,
  state_code VARCHAR(20) NOT NULL,
  zip_code VARCHAR(20) NOT NULL,
  service_address VARCHAR(300) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS zp_account_location (
  acc_loc_id VARCHAR(50) PRIMARY KEY,
  account_id VARCHAR(50) NOT NULL REFERENCES zp_account(account_id),
  customer_id VARCHAR(50) NOT NULL REFERENCES zp_customer(customer_id),
  location_id VARCHAR(50) NOT NULL REFERENCES zp_location(location_id),
  customer_loc_id VARCHAR(50) NOT NULL UNIQUE,
  service_status VARCHAR(30) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS zp_connection (
  zp_connection_id VARCHAR(50) PRIMARY KEY,
  account_id VARCHAR(50) NOT NULL REFERENCES zp_account(account_id),
  customer_id VARCHAR(50) NOT NULL REFERENCES zp_customer(customer_id),
  location_id VARCHAR(50) NOT NULL REFERENCES zp_location(location_id),
  acc_loc_id VARCHAR(50) NOT NULL REFERENCES zp_account_location(acc_loc_id),
  customer_loc_id VARCHAR(50) NOT NULL,
  zapper_id VARCHAR(50) NOT NULL UNIQUE,
  connection_order_number VARCHAR(50) NOT NULL,
  connection_status VARCHAR(30) NOT NULL,
  connected_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS zp_inventory_service (
  inventory_id VARCHAR(50) PRIMARY KEY,
  account_id VARCHAR(50) NOT NULL REFERENCES zp_account(account_id),
  customer_id VARCHAR(50) NOT NULL REFERENCES zp_customer(customer_id),
  acc_loc_id VARCHAR(50) NOT NULL REFERENCES zp_account_location(acc_loc_id),
  zp_connection_id VARCHAR(50) NOT NULL REFERENCES zp_connection(zp_connection_id),
  zapper_id VARCHAR(50) NOT NULL REFERENCES zp_connection(zapper_id),
  inventory_status VARCHAR(30) NOT NULL,
  provisioned_flag BOOLEAN NOT NULL DEFAULT TRUE,
  inventory_sync_status VARCHAR(30) NOT NULL,
  last_verified_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS zp_location_validation (
  validation_id VARCHAR(50) PRIMARY KEY,
  location_id VARCHAR(50) NOT NULL REFERENCES zp_location(location_id),
  zp_request_id VARCHAR(50),
  validated_zip_code VARCHAR(20) NOT NULL,
  zip_match_flag BOOLEAN NOT NULL,
  validation_status VARCHAR(30) NOT NULL,
  validated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS zp_request (
  zp_request_id VARCHAR(50) PRIMARY KEY,
  zp_connection_id VARCHAR(50) REFERENCES zp_connection(zp_connection_id),
  account_id VARCHAR(50) NOT NULL REFERENCES zp_account(account_id),
  customer_id VARCHAR(50) NOT NULL REFERENCES zp_customer(customer_id),
  location_id VARCHAR(50) NOT NULL REFERENCES zp_location(location_id),
  acc_loc_id VARCHAR(50) NOT NULL REFERENCES zp_account_location(acc_loc_id),
  zp_customer_name VARCHAR(150) NOT NULL,
  zp_cust_zip VARCHAR(20) NOT NULL,
  zp_contact_number VARCHAR(30),
  request_type VARCHAR(50) NOT NULL,
  requested_provider VARCHAR(50) NOT NULL,
  request_status VARCHAR(30) NOT NULL,
  requested_at TIMESTAMPTZ NOT NULL,
  submitted_to_aso_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS zp_ui_data (
  zp_request_id VARCHAR(50) PRIMARY KEY REFERENCES zp_request(zp_request_id),
  zp_asr_team_member_id VARCHAR(50),
  zp_asr_team_notes TEXT,
  zp_action_id VARCHAR(10) NOT NULL,
  zp_queue_code VARCHAR(50),
  last_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS zp_ui_data_history (
  history_id VARCHAR(50) PRIMARY KEY,
  zp_request_id VARCHAR(50) NOT NULL REFERENCES zp_request(zp_request_id),
  zp_asr_team_member_id VARCHAR(50),
  zp_asr_team_notes TEXT,
  zp_action_id VARCHAR(10) NOT NULL,
  changed_by VARCHAR(50),
  created_date TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS zp_disconnect_order (
  don VARCHAR(50) PRIMARY KEY,
  zp_request_id VARCHAR(50) NOT NULL REFERENCES zp_request(zp_request_id),
  zp_connection_id VARCHAR(50) NOT NULL REFERENCES zp_connection(zp_connection_id),
  account_id VARCHAR(50) NOT NULL REFERENCES zp_account(account_id),
  acc_loc_id VARCHAR(50) NOT NULL REFERENCES zp_account_location(acc_loc_id),
  zapper_id VARCHAR(50) NOT NULL REFERENCES zp_connection(zapper_id),
  submit_status VARCHAR(30) NOT NULL,
  submit_channel VARCHAR(50) NOT NULL,
  submitted_at TIMESTAMPTZ NOT NULL,
  downstream_status VARCHAR(50) NOT NULL,
  zp_disconnect_id VARCHAR(50),
  disconnect_due_at TIMESTAMPTZ,
  last_checked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS zp_order_downstream_check (
  check_id VARCHAR(50) PRIMARY KEY,
  don VARCHAR(50) NOT NULL REFERENCES zp_disconnect_order(don),
  check_code VARCHAR(50) NOT NULL,
  check_label VARCHAR(150) NOT NULL,
  check_status VARCHAR(30) NOT NULL,
  failure_reason VARCHAR(300),
  checked_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_zp_order_downstream_check UNIQUE (don, check_code)
);

CREATE TABLE IF NOT EXISTS zp_billbank_record (
  billbank_id VARCHAR(50) PRIMARY KEY,
  account_id VARCHAR(50) NOT NULL REFERENCES zp_account(account_id),
  zp_connection_id VARCHAR(50) NOT NULL REFERENCES zp_connection(zp_connection_id),
  zapper_id VARCHAR(50) NOT NULL REFERENCES zp_connection(zapper_id),
  zp_disconnect_id VARCHAR(50),
  bill_plan_code VARCHAR(50) NOT NULL,
  current_billing_cost NUMERIC(12,2) NOT NULL DEFAULT 0,
  bill_cease_status VARCHAR(30) NOT NULL,
  termination_fee_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
  overdue_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
  pending_bill_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
  record_status VARCHAR(30) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS zp_notification_event (
  notification_id VARCHAR(50) PRIMARY KEY,
  zp_request_id VARCHAR(50) REFERENCES zp_request(zp_request_id),
  don VARCHAR(50) REFERENCES zp_disconnect_order(don),
  notification_type VARCHAR(50) NOT NULL,
  notification_status VARCHAR(30) NOT NULL,
  recipient_email VARCHAR(200),
  sent_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_zp_request_lookup
  ON zp_request (account_id, customer_id, acc_loc_id, request_status, requested_at);
CREATE INDEX IF NOT EXISTS idx_zp_request_customer
  ON zp_request (zp_customer_name, zp_cust_zip);
CREATE INDEX IF NOT EXISTS idx_zp_ui_history_request_action_date
  ON zp_ui_data_history (zp_request_id, zp_action_id, created_date);
CREATE INDEX IF NOT EXISTS idx_zp_inventory_acc_loc
  ON zp_inventory_service (acc_loc_id, inventory_status);
CREATE INDEX IF NOT EXISTS idx_zp_disconnect_order_request
  ON zp_disconnect_order (zp_request_id, submitted_at, downstream_status, zp_disconnect_id);
CREATE INDEX IF NOT EXISTS idx_zp_billbank_disconnect
  ON zp_billbank_record (zp_disconnect_id, record_status);

