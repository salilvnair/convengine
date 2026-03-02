-- Zapper demo transaction schema (SQLite)

CREATE TABLE IF NOT EXISTS zp_customer (
  customer_id TEXT PRIMARY KEY,
  customer_name TEXT NOT NULL,
  customer_type TEXT NOT NULL,
  contact_number TEXT,
  email_address TEXT,
  customer_status TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS zp_account (
  account_id TEXT PRIMARY KEY,
  customer_id TEXT NOT NULL,
  provider_code TEXT NOT NULL,
  bill_plan_code TEXT NOT NULL,
  current_billing_cost NUMERIC NOT NULL DEFAULT 0,
  account_status TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (customer_id) REFERENCES zp_customer(customer_id)
);

CREATE TABLE IF NOT EXISTS zp_location (
  location_id TEXT PRIMARY KEY,
  location_name TEXT NOT NULL,
  city_name TEXT NOT NULL,
  state_code TEXT NOT NULL,
  zip_code TEXT NOT NULL,
  service_address TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS zp_account_location (
  acc_loc_id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  location_id TEXT NOT NULL,
  customer_loc_id TEXT NOT NULL UNIQUE,
  service_status TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (account_id) REFERENCES zp_account(account_id),
  FOREIGN KEY (customer_id) REFERENCES zp_customer(customer_id),
  FOREIGN KEY (location_id) REFERENCES zp_location(location_id)
);

CREATE TABLE IF NOT EXISTS zp_connection (
  zp_connection_id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  location_id TEXT NOT NULL,
  acc_loc_id TEXT NOT NULL,
  customer_loc_id TEXT NOT NULL,
  zapper_id TEXT NOT NULL UNIQUE,
  connection_order_number TEXT NOT NULL,
  connection_status TEXT NOT NULL,
  connected_at TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (account_id) REFERENCES zp_account(account_id),
  FOREIGN KEY (customer_id) REFERENCES zp_customer(customer_id),
  FOREIGN KEY (location_id) REFERENCES zp_location(location_id),
  FOREIGN KEY (acc_loc_id) REFERENCES zp_account_location(acc_loc_id)
);

CREATE TABLE IF NOT EXISTS zp_inventory_service (
  inventory_id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  acc_loc_id TEXT NOT NULL,
  zp_connection_id TEXT NOT NULL,
  zapper_id TEXT NOT NULL,
  inventory_status TEXT NOT NULL,
  provisioned_flag INTEGER NOT NULL DEFAULT 1,
  inventory_sync_status TEXT NOT NULL,
  last_verified_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (account_id) REFERENCES zp_account(account_id),
  FOREIGN KEY (customer_id) REFERENCES zp_customer(customer_id),
  FOREIGN KEY (acc_loc_id) REFERENCES zp_account_location(acc_loc_id),
  FOREIGN KEY (zp_connection_id) REFERENCES zp_connection(zp_connection_id)
);

CREATE TABLE IF NOT EXISTS zp_location_validation (
  validation_id TEXT PRIMARY KEY,
  location_id TEXT NOT NULL,
  zp_request_id TEXT,
  validated_zip_code TEXT NOT NULL,
  zip_match_flag INTEGER NOT NULL,
  validation_status TEXT NOT NULL,
  validated_at TEXT NOT NULL,
  FOREIGN KEY (location_id) REFERENCES zp_location(location_id)
);

CREATE TABLE IF NOT EXISTS zp_request (
  zp_request_id TEXT PRIMARY KEY,
  zp_connection_id TEXT,
  account_id TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  location_id TEXT NOT NULL,
  acc_loc_id TEXT NOT NULL,
  zp_customer_name TEXT NOT NULL,
  zp_cust_zip TEXT NOT NULL,
  zp_contact_number TEXT,
  request_type TEXT NOT NULL,
  requested_provider TEXT NOT NULL,
  request_status TEXT NOT NULL,
  requested_at TEXT NOT NULL,
  submitted_to_aso_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (zp_connection_id) REFERENCES zp_connection(zp_connection_id),
  FOREIGN KEY (account_id) REFERENCES zp_account(account_id),
  FOREIGN KEY (customer_id) REFERENCES zp_customer(customer_id),
  FOREIGN KEY (location_id) REFERENCES zp_location(location_id),
  FOREIGN KEY (acc_loc_id) REFERENCES zp_account_location(acc_loc_id)
);

CREATE TABLE IF NOT EXISTS zp_ui_data (
  zp_request_id TEXT PRIMARY KEY,
  zp_asr_team_member_id TEXT,
  zp_asr_team_notes TEXT,
  zp_action_id TEXT NOT NULL,
  zp_queue_code TEXT,
  last_updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (zp_request_id) REFERENCES zp_request(zp_request_id)
);

CREATE TABLE IF NOT EXISTS zp_ui_data_history (
  history_id TEXT PRIMARY KEY,
  zp_request_id TEXT NOT NULL,
  zp_asr_team_member_id TEXT,
  zp_asr_team_notes TEXT,
  zp_action_id TEXT NOT NULL,
  changed_by TEXT,
  created_date TEXT NOT NULL,
  FOREIGN KEY (zp_request_id) REFERENCES zp_request(zp_request_id)
);

CREATE TABLE IF NOT EXISTS zp_disconnect_order (
  don TEXT PRIMARY KEY,
  zp_request_id TEXT NOT NULL,
  zp_connection_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  acc_loc_id TEXT NOT NULL,
  zapper_id TEXT NOT NULL,
  submit_status TEXT NOT NULL,
  submit_channel TEXT NOT NULL,
  submitted_at TEXT NOT NULL,
  downstream_status TEXT NOT NULL,
  zp_disconnect_id TEXT,
  disconnect_due_at TEXT,
  last_checked_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (zp_request_id) REFERENCES zp_request(zp_request_id),
  FOREIGN KEY (zp_connection_id) REFERENCES zp_connection(zp_connection_id),
  FOREIGN KEY (account_id) REFERENCES zp_account(account_id),
  FOREIGN KEY (acc_loc_id) REFERENCES zp_account_location(acc_loc_id)
);

CREATE TABLE IF NOT EXISTS zp_order_downstream_check (
  check_id TEXT PRIMARY KEY,
  don TEXT NOT NULL,
  check_code TEXT NOT NULL,
  check_label TEXT NOT NULL,
  check_status TEXT NOT NULL,
  failure_reason TEXT,
  checked_at TEXT NOT NULL,
  UNIQUE (don, check_code),
  FOREIGN KEY (don) REFERENCES zp_disconnect_order(don)
);

CREATE TABLE IF NOT EXISTS zp_billbank_record (
  billbank_id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  zp_connection_id TEXT NOT NULL,
  zapper_id TEXT NOT NULL,
  zp_disconnect_id TEXT,
  bill_plan_code TEXT NOT NULL,
  current_billing_cost NUMERIC NOT NULL DEFAULT 0,
  bill_cease_status TEXT NOT NULL,
  termination_fee_amount NUMERIC NOT NULL DEFAULT 0,
  overdue_amount NUMERIC NOT NULL DEFAULT 0,
  pending_bill_amount NUMERIC NOT NULL DEFAULT 0,
  record_status TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (account_id) REFERENCES zp_account(account_id),
  FOREIGN KEY (zp_connection_id) REFERENCES zp_connection(zp_connection_id)
);

CREATE TABLE IF NOT EXISTS zp_notification_event (
  notification_id TEXT PRIMARY KEY,
  zp_request_id TEXT,
  don TEXT,
  notification_type TEXT NOT NULL,
  notification_status TEXT NOT NULL,
  recipient_email TEXT,
  sent_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (zp_request_id) REFERENCES zp_request(zp_request_id),
  FOREIGN KEY (don) REFERENCES zp_disconnect_order(don)
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

