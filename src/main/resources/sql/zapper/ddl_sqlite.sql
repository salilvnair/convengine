-- Zapper Electricity Enterprise Disconnect schema (SQLite)

DROP TABLE IF EXISTS zp_disco_trans_data;
DROP TABLE IF EXISTS zp_inventory_data;
DROP TABLE IF EXISTS zp_action_status;
DROP TABLE IF EXISTS zp_disco_request_log;
DROP TABLE IF EXISTS zp_disco_request;

CREATE TABLE IF NOT EXISTS zp_disco_request (
  request_id TEXT PRIMARY KEY,
  customer_name TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  feeder_id TEXT NOT NULL,
  transformer_connection_id TEXT NOT NULL,
  plan_id TEXT,
  address_location TEXT NOT NULL,
  signed_disconnect_document INTEGER NOT NULL DEFAULT 0,
  status INTEGER NOT NULL DEFAULT 0,
  status_reason TEXT,
  assigned_team TEXT,
  sync_status_code INTEGER,
  async_status_code INTEGER,
  soft_disconnect_at TEXT,
  hard_disconnect_due_at TEXT,
  hard_disconnect_at TEXT,
  feeder_closed_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (status IN (0, 120, 200, 404, 500, 700, 710, 800, 810, 835, 840, 841, 850, 855))
);

CREATE TABLE IF NOT EXISTS zp_disco_request_log (
  log_id INTEGER PRIMARY KEY AUTOINCREMENT,
  scenario_id TEXT NOT NULL,
  request_id TEXT NOT NULL,
  customer_name TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  feeder_id TEXT NOT NULL,
  transformer_connection_id TEXT NOT NULL,
  plan_id TEXT,
  address_location TEXT NOT NULL,
  signed_disconnect_document INTEGER NOT NULL DEFAULT 0,
  status INTEGER NOT NULL DEFAULT 0,
  status_reason TEXT,
  assigned_team TEXT,
  sync_status_code INTEGER,
  async_status_code INTEGER,
  soft_disconnect_at TEXT,
  hard_disconnect_due_at TEXT,
  hard_disconnect_at TEXT,
  feeder_closed_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  logged_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (status IN (0, 120, 200, 404, 500, 700, 710, 800, 810, 835, 840, 841, 850, 855)),
  FOREIGN KEY (request_id) REFERENCES zp_disco_request(request_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS zp_action_status (
  action_id INTEGER PRIMARY KEY,
  action_value TEXT NOT NULL,
  status INTEGER NOT NULL,
  nxt_status INTEGER NOT NULL,
  team INTEGER NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  CHECK (team IN (1,2))
);

CREATE TABLE IF NOT EXISTS zp_inventory_data (
  inventory_id INTEGER PRIMARY KEY AUTOINCREMENT,
  request_id TEXT NOT NULL UNIQUE,
  feeder_id TEXT NOT NULL,
  transformer_connection_id TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  customer_name TEXT NOT NULL,
  plan_id TEXT,
  address_location TEXT NOT NULL,
  feeder_state TEXT NOT NULL DEFAULT 'ACTIVE',
  feeder_closed_date TEXT,
  last_verified_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (request_id) REFERENCES zp_disco_request(request_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS zp_disco_trans_data (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  request_id TEXT NOT NULL,
  action_id INTEGER NOT NULL,
  status INTEGER NOT NULL,
  disconnect_order_no TEXT,
  logged_user_id TEXT NOT NULL,
  notes_text TEXT,
  due_date TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (request_id) REFERENCES zp_disco_request(request_id) ON DELETE CASCADE,
  FOREIGN KEY (action_id) REFERENCES zp_action_status(action_id)
);

CREATE INDEX IF NOT EXISTS idx_zp_disco_request_status ON zp_disco_request (status, assigned_team, updated_at);
CREATE INDEX IF NOT EXISTS idx_zp_disco_request_customer ON zp_disco_request (customer_id, feeder_id, transformer_connection_id);
CREATE INDEX IF NOT EXISTS idx_zp_disco_request_log_request ON zp_disco_request_log (request_id, logged_at);
CREATE INDEX IF NOT EXISTS idx_zp_disco_request_log_scenario ON zp_disco_request_log (scenario_id, status, logged_at);
CREATE INDEX IF NOT EXISTS idx_zp_inventory_data_lookup ON zp_inventory_data (request_id, customer_id, feeder_id, transformer_connection_id);
CREATE INDEX IF NOT EXISTS idx_zp_disco_trans_request ON zp_disco_trans_data (request_id, status, action_id, created_at);
