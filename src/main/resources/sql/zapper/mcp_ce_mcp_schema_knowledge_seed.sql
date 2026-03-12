-- Schema knowledge seed for electricity disconnect demo schema.

ALTER TABLE IF EXISTS ce_mcp_schema_knowledge
  ADD COLUMN IF NOT EXISTS valid_values VARCHAR(2000);

INSERT INTO ce_mcp_schema_knowledge (id, table_name, column_name, valid_values, description, tags)
VALUES
  (31001, 'zp_disco_request', NULL, NULL, 'Disconnect request master table.', 'disconnect,request,master'),
  (31002, 'zp_disco_request', 'request_id', NULL, 'Primary request identifier.', 'request,id,pk'),
  (31003, 'zp_disco_request', 'status', '0,120,200,404,500,700,710,800,810,835,840,841,850,855', 'Request lifecycle status code.', 'status,lifecycle'),
  (31004, 'zp_disco_request', 'feeder_id', NULL, 'Feeder identifier.', 'feeder,id'),
  (31005, 'zp_disco_request', 'transformer_connection_id', NULL, 'Transformer connection identifier.', 'transformer,connection,id'),
  (31006, 'zp_disco_request', 'customer_id', NULL, 'Customer identifier.', 'customer,id'),
  (31007, 'zp_inventory_data', NULL, NULL, 'Inventory validation data table.', 'inventory,validation'),
  (31008, 'zp_inventory_data', 'request_id', NULL, 'Request FK to zp_disco_request.', 'request,fk'),
  (31009, 'zp_inventory_data', 'feeder_state', 'ACTIVE,MISSING,CLOSED', 'Feeder state from inventory.', 'feeder,state'),
  (31010, 'zp_disco_trans_data', NULL, NULL, 'Transaction/action trail table.', 'transaction,action,trail'),
  (31011, 'zp_disco_trans_data', 'action_id', '1000,2000,3000,4000,5000,6000,7000', 'Workflow action id.', 'action,id'),
  (31012, 'zp_disco_trans_data', 'status', '700,710,800,810,835,840,120', 'Status recorded for the action event.', 'status,event'),
  (31013, 'zp_disco_trans_data', 'disconnect_order_no', NULL, 'Disconnect order number received from async flow.', 'disconnect,order,no'),
  (31014, 'zp_action_status', NULL, NULL, 'Static action-to-status transition matrix.', 'action,status,matrix'),
  (31015, 'zp_action_status', 'action_id', '1000,2000,3000,4000,5000,6000,7000', 'Action identifier.', 'action,id,pk'),
  (31016, 'zp_action_status', 'status', '700,710,800,810', 'Current status expected for action.', 'current,status'),
  (31017, 'zp_action_status', 'nxt_status', '710,800,120,810,700,835', 'Next status after action.', 'next,status'),
  (31018, 'zp_action_status', 'team', '1,2', 'Owning team for action.', 'team')
ON CONFLICT (id) DO UPDATE SET
  table_name = EXCLUDED.table_name,
  column_name = EXCLUDED.column_name,
  valid_values = EXCLUDED.valid_values,
  description = EXCLUDED.description,
  tags = EXCLUDED.tags;
