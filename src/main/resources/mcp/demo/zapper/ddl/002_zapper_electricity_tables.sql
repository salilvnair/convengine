-- Zapper Electricity Enterprise Disconnect schema (Postgres)

DROP TABLE IF EXISTS zp_disco_trans_data;
DROP TABLE IF EXISTS zp_inventory_data;
DROP TABLE IF EXISTS zp_action_status;
DROP TABLE IF EXISTS zp_disco_request_log;
DROP TABLE IF EXISTS zp_disco_request;

CREATE TABLE IF NOT EXISTS zp_disco_request (
  request_id VARCHAR(64) PRIMARY KEY,
  customer_name VARCHAR(200) NOT NULL,
  customer_id VARCHAR(64) NOT NULL,
  feeder_id VARCHAR(64) NOT NULL,
  transformer_connection_id VARCHAR(64) NOT NULL,
  plan_id VARCHAR(64),
  address_location VARCHAR(255) NOT NULL,
  signed_disconnect_document BOOLEAN NOT NULL DEFAULT FALSE,
  status INTEGER NOT NULL DEFAULT 0,
  status_reason VARCHAR(500),
  assigned_team VARCHAR(64),
  sync_status_code INTEGER,
  async_status_code INTEGER,
  soft_disconnect_at TIMESTAMPTZ,
  hard_disconnect_due_at TIMESTAMPTZ,
  hard_disconnect_at TIMESTAMPTZ,
  feeder_closed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_zp_disco_request_status CHECK (status IN (0, 120, 200, 404, 500, 700, 710, 800, 810, 835, 840, 841, 850, 855))
);

CREATE TABLE IF NOT EXISTS zp_disco_request_log (
  log_id BIGSERIAL PRIMARY KEY,
  scenario_id VARCHAR(64) NOT NULL,
  request_id VARCHAR(64) NOT NULL REFERENCES zp_disco_request(request_id) ON DELETE CASCADE,
  customer_name VARCHAR(200) NOT NULL,
  customer_id VARCHAR(64) NOT NULL,
  feeder_id VARCHAR(64) NOT NULL,
  transformer_connection_id VARCHAR(64) NOT NULL,
  plan_id VARCHAR(64),
  address_location VARCHAR(255) NOT NULL,
  signed_disconnect_document BOOLEAN NOT NULL DEFAULT FALSE,
  status INTEGER NOT NULL DEFAULT 0,
  status_reason VARCHAR(500),
  assigned_team VARCHAR(64),
  sync_status_code INTEGER,
  async_status_code INTEGER,
  soft_disconnect_at TIMESTAMPTZ,
  hard_disconnect_due_at TIMESTAMPTZ,
  hard_disconnect_at TIMESTAMPTZ,
  feeder_closed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  logged_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_zp_disco_request_log_status CHECK (status IN (0, 120, 200, 404, 500, 700, 710, 800, 810, 835, 840, 841, 850, 855))
);

CREATE TABLE IF NOT EXISTS zp_action_status (
  action_id INTEGER PRIMARY KEY,
  action_value VARCHAR(100) NOT NULL,
  status INTEGER NOT NULL,
  nxt_status INTEGER NOT NULL,
  team INTEGER NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT chk_zp_action_status_team CHECK (team IN (1,2))
);

CREATE TABLE IF NOT EXISTS zp_inventory_data (
  inventory_id BIGSERIAL PRIMARY KEY,
  request_id VARCHAR(64) NOT NULL REFERENCES zp_disco_request(request_id) ON DELETE CASCADE,
  feeder_id VARCHAR(64) NOT NULL,
  transformer_connection_id VARCHAR(64) NOT NULL,
  customer_id VARCHAR(64) NOT NULL,
  customer_name VARCHAR(200) NOT NULL,
  plan_id VARCHAR(64),
  address_location VARCHAR(255) NOT NULL,
  feeder_state VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  feeder_closed_date DATE,
  last_verified_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_zp_inventory_data_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS zp_disco_trans_data (
  id BIGSERIAL PRIMARY KEY,
  request_id VARCHAR(64) NOT NULL REFERENCES zp_disco_request(request_id) ON DELETE CASCADE,
  action_id INTEGER NOT NULL REFERENCES zp_action_status(action_id),
  status INTEGER NOT NULL,
  disconnect_order_no VARCHAR(80),
  logged_user_id VARCHAR(64) NOT NULL,
  notes_text TEXT,
  due_date DATE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_zp_disco_request_status ON zp_disco_request (status, assigned_team, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_zp_disco_request_customer ON zp_disco_request (customer_id, feeder_id, transformer_connection_id);
CREATE INDEX IF NOT EXISTS idx_zp_disco_request_log_request ON zp_disco_request_log (request_id, logged_at DESC);
CREATE INDEX IF NOT EXISTS idx_zp_disco_request_log_scenario ON zp_disco_request_log (scenario_id, status, logged_at DESC);
CREATE INDEX IF NOT EXISTS idx_zp_inventory_data_lookup ON zp_inventory_data (request_id, customer_id, feeder_id, transformer_connection_id);
CREATE INDEX IF NOT EXISTS idx_zp_disco_trans_request ON zp_disco_trans_data (request_id, status, action_id, created_at DESC);
