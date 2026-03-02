-- ConvEngine MCP DB Knowledge Graph package seed
-- Purpose:
--   Seed the metadata model with a Zapper-oriented investigation setup.
--
-- Scope:
--   - Seeds metadata only.
--   - Does NOT insert into consumer transaction tables such as zp_request.
--   - References only the transaction tables/columns that were explicitly
--     described in the scenario.
--
-- Important:
--   1) This file assumes the core ConvEngine tables already exist.
--   2) This file assumes the DBKG tables in this folder already exist.
--   3) Placeholder query templates are included and disabled where the scenario
--      did not define the actual consumer-owned transaction table names.

-- ---------------------------------------------------------------------------
-- 0) Register DBKG MCP tool codes in the existing core tool registry
-- ---------------------------------------------------------------------------
INSERT INTO ce_mcp_tool (tool_id, tool_code, tool_group, intent_code, state_code, enabled, description, created_at)
VALUES
  (9101, 'dbkg.case.resolve', 'DB', 'ANY', 'ANY', TRUE, 'Resolve business case from user language using DB metadata.', NOW()),
  (9102, 'dbkg.knowledge.lookup', 'DB', 'ANY', 'ANY', TRUE, 'Lookup graph metadata: entities, systems, tables, joins, statuses, and lineage.', NOW()),
  (9103, 'dbkg.investigate.plan', 'DB', 'ANY', 'ANY', TRUE, 'Choose the highest-confidence playbook and build a step plan from DB metadata.', NOW()),
  (9104, 'dbkg.investigate.execute', 'DB', 'ANY', 'ANY', TRUE, 'Execute the selected investigation playbook using DB-configured steps and templates.', NOW()),
  (9105, 'dbkg.playbook.validate', 'DB', 'ANY', 'ANY', TRUE, 'Validate a DBKG playbook graph without executing its investigation steps.', NOW())
ON CONFLICT (tool_code) DO UPDATE
SET
  tool_group = EXCLUDED.tool_group,
  intent_code = EXCLUDED.intent_code,
  state_code = EXCLUDED.state_code,
  enabled = EXCLUDED.enabled,
  description = EXCLUDED.description;

-- ---------------------------------------------------------------------------
-- 0b) Optional DBKG-specific planner prompt
-- ---------------------------------------------------------------------------
-- This codebase does not have a ce_mcp_tool_step orchestration table.
-- The planner prompt is the DB-seeded control point for tool sequencing.
--
-- This planner is intentionally scoped to a dedicated intent/state so it does
-- not override the global ANY/ANY planner. Use this scope when you want the
-- guarded DBKG path:
--   intent_code = DBKG_DIAGNOSTICS
--   state_code  = ANALYZE
--
-- Expected tool order:
--   1) dbkg.case.resolve
--   2) dbkg.knowledge.lookup
--   3) dbkg.investigate.plan
--   4) dbkg.playbook.validate
--   5) Only if valid=true and user wants execution: dbkg.investigate.execute
INSERT INTO ce_mcp_planner (planner_id, intent_code, state_code, system_prompt, user_prompt, enabled, created_at)
VALUES
(
  5901,
  'DBKG_DIAGNOSTICS',
  'ANALYZE',
  'You are an MCP planning agent for DB-backed knowledge-graph investigations. You MUST use the guarded DBKG sequence. Tool order:\n1) dbkg.case.resolve to identify the likely case.\n2) dbkg.knowledge.lookup to collect relevant entities, systems, schema, API flows, joins, statuses, and lineage.\n3) dbkg.investigate.plan to select the best playbook.\n4) dbkg.playbook.validate before any execution.\n5) Call dbkg.investigate.execute only if validation says valid=true and canExecute=true.\n6) If validation fails, ANSWER with the graphError or summary and do not execute.\n7) After dbkg.investigate.execute, ANSWER using finalSummary or outcome.explanation from the latest observation.\n8) If enough observations already exist, ANSWER concisely from observations.\nReturn JSON only. Never skip validation before execution.',
  'User input:\n{{user_input}}\n\nContext JSON:\n{{context}}\n\nAvailable MCP tools:\n{{mcp_tools}}\n\nExisting MCP observations:\n{{mcp_observations}}\n\nReturn strict JSON:\n{\n  "action":"CALL_TOOL" | "ANSWER",\n  "tool_code":"<tool_code_or_null>",\n  "args":{},\n  "answer":"<text_or_null>"\n}',
  TRUE,
  NOW()
)
ON CONFLICT (planner_id) DO UPDATE
SET
  intent_code = EXCLUDED.intent_code,
  state_code = EXCLUDED.state_code,
  system_prompt = EXCLUDED.system_prompt,
  user_prompt = EXCLUDED.user_prompt,
  enabled = EXCLUDED.enabled;

-- ---------------------------------------------------------------------------
-- 1) Generic executors
-- ---------------------------------------------------------------------------
INSERT INTO ce_mcp_executor_template (executor_code, executor_type, config_schema_json, description, enabled, created_at)
VALUES
  ('CASE_SIGNAL_MATCHER', 'DECISION',
   '{"inputs":["userText"],"outputs":["caseCode","scoreBreakdown"],"notes":"Generic weighted matcher over ce_mcp_case_signal."}',
   'Scores user language against case signals and returns ranked business cases.', TRUE, NOW()),
  ('PLAYBOOK_SIGNAL_MATCHER', 'DECISION',
   '{"inputs":["userText","caseCode"],"outputs":["playbookCode","scoreBreakdown"],"notes":"Generic weighted matcher over ce_mcp_playbook_signal."}',
   'Scores user language against playbook signals under the selected case.', TRUE, NOW()),
  ('STATUS_LOOKUP', 'LOOKUP',
   '{"inputs":["dictionaryName","fieldName","labels"],"outputs":["resolvedCodes"],"notes":"Maps labels like ASSIGNED/REJECTED to code values."}',
   'Resolves business-friendly status words into actual DB code values.', TRUE, NOW()),
  ('TIME_WINDOW_DERIVER', 'DERIVED_FIELD',
   '{"inputs":["hours"],"outputs":["fromTs"],"notes":"Computes a lower-bound timestamp from now minus N hours."}',
   'Builds reusable rolling time-window inputs.', TRUE, NOW()),
  ('QUERY_TEMPLATE_EXECUTOR', 'SQL',
   '{"inputs":["queryCode","params"],"outputs":["rows"],"notes":"Executes approved SELECT-only SQL templates with bound params."}',
   'Runs approved SQL templates from ce_mcp_query_template.', TRUE, NOW()),
  ('SUMMARY_RENDERER', 'SUMMARY',
   '{"inputs":["rows","context"],"outputs":["summary"],"notes":"Renders operator-facing findings from step outputs."}',
   'Builds a final explanation from investigation outputs and outcome rules.', TRUE, NOW())
ON CONFLICT (executor_code) DO UPDATE
SET
  executor_type = EXCLUDED.executor_type,
  config_schema_json = EXCLUDED.config_schema_json,
  description = EXCLUDED.description,
  enabled = EXCLUDED.enabled;

-- ---------------------------------------------------------------------------
-- 2) Business cases
-- ---------------------------------------------------------------------------
INSERT INTO ce_mcp_case_type (case_code, case_name, description, intent_code, state_code, priority, enabled, created_at)
VALUES
  ('SERVICE_DISCONNECT', 'Service Disconnect',
   'Investigations related to disconnect lifecycle, ASR review, ASO submission, and downstream order processing.',
   'ANY', 'ANY', 10, TRUE, NOW()),
  ('INVENTORY_MISMATCH', 'Inventory Mismatch',
   'Investigations where the operator sees inventory not found or provisioning/inventory lineage mismatches.',
   'ANY', 'ANY', 20, TRUE, NOW()),
  ('DISCONNECT_SUBMIT_FAILURE', 'Disconnect Submit Failure',
   'Investigations where submit succeeded but downstream disconnect IDs or orders were not created in time.',
   'ANY', 'ANY', 30, TRUE, NOW()),
  ('BILLING_RECONCILIATION', 'Billing Reconciliation',
   'Investigations where disconnect/order identifiers are inconsistent between order and billing systems.',
   'ANY', 'ANY', 40, TRUE, NOW())
ON CONFLICT (case_code) DO UPDATE
SET
  case_name = EXCLUDED.case_name,
  description = EXCLUDED.description,
  intent_code = EXCLUDED.intent_code,
  state_code = EXCLUDED.state_code,
  priority = EXCLUDED.priority,
  enabled = EXCLUDED.enabled;

INSERT INTO ce_mcp_case_signal (case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES
  ('SERVICE_DISCONNECT', 'KEYWORD', 'CONTAINS', 'disconnect', 50.00, FALSE, TRUE, 'Broad disconnect intent signal.'),
  ('SERVICE_DISCONNECT', 'KEYWORD', 'CONTAINS', 'ASR', 10.00, FALSE, TRUE, 'Mentions the ASR team.'),
  ('SERVICE_DISCONNECT', 'KEYWORD', 'CONTAINS', 'ASO', 10.00, FALSE, TRUE, 'Mentions the ASO team.'),
  ('SERVICE_DISCONNECT', 'KEYWORD', 'CONTAINS', 'ZapperCentral', 5.00, FALSE, TRUE, 'Mentions the central orchestration app.'),
  ('INVENTORY_MISMATCH', 'KEYWORD', 'CONTAINS', 'inventory not found', 60.00, TRUE, TRUE, 'Classic ASR inventory symptom.'),
  ('INVENTORY_MISMATCH', 'KEYWORD', 'CONTAINS', 'inventory', 25.00, FALSE, TRUE, 'Broad inventory signal.'),
  ('INVENTORY_MISMATCH', 'KEYWORD', 'CONTAINS', 'accLocId', 15.00, FALSE, TRUE, 'Key inventory lookup identifier.'),
  ('DISCONNECT_SUBMIT_FAILURE', 'KEYWORD', 'CONTAINS', 'zpDisconnectId', 50.00, TRUE, TRUE, 'Missing disconnect ID symptom.'),
  ('DISCONNECT_SUBMIT_FAILURE', 'KEYWORD', 'CONTAINS', '24h', 20.00, FALSE, TRUE, 'SLA breach wording.'),
  ('DISCONNECT_SUBMIT_FAILURE', 'KEYWORD', 'CONTAINS', '24hr', 20.00, FALSE, TRUE, 'Alternate SLA wording.'),
  ('DISCONNECT_SUBMIT_FAILURE', 'KEYWORD', 'CONTAINS', 'submitted', 15.00, FALSE, TRUE, 'Submit event wording.'),
  ('BILLING_RECONCILIATION', 'KEYWORD', 'CONTAINS', 'BillBank', 40.00, TRUE, TRUE, 'Billing system signal.'),
  ('BILLING_RECONCILIATION', 'KEYWORD', 'CONTAINS', 'reconciliation', 30.00, FALSE, TRUE, 'Explicit reconciliation wording.'),
  ('BILLING_RECONCILIATION', 'KEYWORD', 'CONTAINS', 'not in BillBank', 35.00, FALSE, TRUE, 'Gap between source and billing.')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3) Domain graph
-- ---------------------------------------------------------------------------
INSERT INTO ce_mcp_domain_entity (entity_code, entity_name, description, synonyms, criticality, metadata_json, llm_hint, enabled, created_at)
VALUES
  ('CUSTOMER', 'Customer', 'The end customer who owns one or more accounts.', 'customer,consumer,account holder', 'HIGH', '{"exampleRoles":["requester","account holder"]}', 'Use when the question is about who owns the service/account.', TRUE, NOW()),
  ('ACCOUNT', 'Account', 'Commercial account maintained for a customer.', 'account,accountId', 'HIGH', '{"primaryIds":["accountId"]}', 'Represents the billing/service account anchor.', TRUE, NOW()),
  ('SERVICE_LOCATION', 'Service Location', 'Physical location where electric service exists.', 'location,service location,locationId', 'HIGH', '{"primaryIds":["locationId"]}', 'Represents the physical service address or site.', TRUE, NOW()),
  ('ACCOUNT_LOCATION', 'Account Location', 'Relationship between account and service location.', 'accLocId,account location,customerLocId', 'HIGH', '{"primaryIds":["accLocId","customerLocId"]}', 'Use when the user refers to account-location linkage identifiers.', TRUE, NOW()),
  ('DISCONNECT_REQUEST', 'Disconnect Request', 'Operator request to disconnect service for an account/location.', 'disconnect request,request,zp_request_id', 'HIGH', '{"primaryIds":["zp_request_id"]}', 'Primary business request before downstream submission.', TRUE, NOW()),
  ('ASR_ASSIGNMENT', 'ASR Assignment', 'ASR review and action state for a request.', 'assignment,review,reject,assign,zp_action_id', 'HIGH', '{"statusField":"zp_action_id"}', 'Use for request state transitions and operator actions.', TRUE, NOW()),
  ('DISCONNECT_ORDER', 'Disconnect Order', 'Downstream order created when the disconnect is submitted.', 'DON,disconnect order,orderNumber', 'HIGH', '{"primaryIds":["DON","orderNumber"]}', 'Represents the downstream order entity.', TRUE, NOW()),
  ('ZAPPER_SERVICE_ID', 'Zapper Service Identifier', 'Service identifier created during connection provisioning and reused during disconnect.', 'zapperId,service id', 'HIGH', '{"primaryIds":["zapperId"]}', 'Stable per-location service identifier across lifecycle.', TRUE, NOW()),
  ('DISCONNECT_ID', 'Disconnect Identifier', 'Final disconnect identifier created after downstream checks succeed.', 'zpDisconnectId,disconnect id', 'HIGH', '{"primaryIds":["zpDisconnectId"]}', 'Final disconnect completion identifier.', TRUE, NOW()),
  ('BILLING_RECORD', 'Billing Record', 'Billing and bill cease record tied to service/disconnect identifiers.', 'billing,billbank,bill cease', 'HIGH', '{"downstreamSystem":"billing"}', 'Represents billing-side visibility for disconnect state.', TRUE, NOW())
ON CONFLICT (entity_code) DO UPDATE
SET
  entity_name = EXCLUDED.entity_name,
  description = EXCLUDED.description,
  synonyms = EXCLUDED.synonyms,
  criticality = EXCLUDED.criticality,
  metadata_json = EXCLUDED.metadata_json,
  llm_hint = EXCLUDED.llm_hint,
  enabled = EXCLUDED.enabled;

INSERT INTO ce_mcp_domain_relation (from_entity_code, relation_type, to_entity_code, cardinality, description, enabled)
VALUES
  ('CUSTOMER', 'OWNS', 'ACCOUNT', 'ONE_TO_MANY', 'A customer may own multiple accounts.', TRUE),
  ('ACCOUNT', 'SERVES', 'SERVICE_LOCATION', 'MANY_TO_MANY', 'Accounts can be tied to one or more service locations.', TRUE),
  ('ACCOUNT', 'HAS', 'ACCOUNT_LOCATION', 'ONE_TO_MANY', 'Each account may have multiple account-location relationships.', TRUE),
  ('ACCOUNT_LOCATION', 'GENERATES', 'DISCONNECT_REQUEST', 'ONE_TO_MANY', 'A disconnect request is raised against a specific account/location.', TRUE),
  ('DISCONNECT_REQUEST', 'TRACKED_BY', 'ASR_ASSIGNMENT', 'ONE_TO_MANY', 'ASR actions record the operator state transitions.', TRUE),
  ('DISCONNECT_REQUEST', 'GENERATES', 'DISCONNECT_ORDER', 'ZERO_TO_ONE', 'A valid submitted disconnect creates a downstream order.', TRUE),
  ('DISCONNECT_ORDER', 'PRODUCES', 'DISCONNECT_ID', 'ZERO_TO_ONE', 'A successful downstream flow creates the final disconnect ID.', TRUE),
  ('DISCONNECT_ID', 'SYNCS_TO', 'BILLING_RECORD', 'ONE_TO_ONE', 'The disconnect ID should be visible in billing after propagation.', TRUE)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4) System graph
-- ---------------------------------------------------------------------------
INSERT INTO ce_mcp_system_node (system_code, system_name, system_type, description, metadata_json, llm_hint, enabled, created_at)
VALUES
  ('ZAPPER_UI', 'Zapper UI', 'UI', 'React UI used by operators.', '{"layer":"frontend"}', 'Entry point where operator actions originate.', TRUE, NOW()),
  ('ZAPPER_CENTRAL', 'ZapperCentral', 'ORCHESTRATOR', 'Central orchestration layer that receives requests and runs nightly processing.', '{"layer":"orchestrator"}', 'Main coordinator that fans out to downstream systems.', TRUE, NOW()),
  ('ZAPPER_INV', 'Zapper Inventory', 'DB_APP', 'Inventory system used to validate service identifiers and connection lineage.', '{"layer":"inventory"}', 'Use when checking whether the service/inventory record exists.', TRUE, NOW()),
  ('ZAPPER_LS', 'Zapper Location Service', 'SERVICE', 'Service used to validate location and zip details.', '{"layer":"validation"}', 'Use when the issue involves location or zip validation.', TRUE, NOW()),
  ('ZAPPER_ORDER_SERVICE', 'Zapper Order Service', 'SERVICE', 'Service that creates disconnect orders and runs downstream order checks.', '{"layer":"order"}', 'Use when a submit should generate order identifiers or disconnect outputs.', TRUE, NOW()),
  ('ZAPPER_BILLBANK', 'Zapper BillBank', 'DB_APP', 'Billing system that should receive billing and disconnect identifiers.', '{"layer":"billing"}', 'Use when reconciling disconnect visibility in billing.', TRUE, NOW())
ON CONFLICT (system_code) DO UPDATE
SET
  system_name = EXCLUDED.system_name,
  system_type = EXCLUDED.system_type,
  description = EXCLUDED.description,
  metadata_json = EXCLUDED.metadata_json,
  llm_hint = EXCLUDED.llm_hint,
  enabled = EXCLUDED.enabled;

INSERT INTO ce_mcp_api_flow (api_code, api_name, system_code, flow_type, description, metadata_json, llm_hint, enabled, created_at)
VALUES
  ('zapper.central.nightly.validation', 'ZapperCentral Nightly Validation', 'ZAPPER_CENTRAL', 'BATCH',
   'Nightly batch flow that validates queued requests before they move deeper into the workflow.',
   '{"calls":["ZAPPER_INV","ZAPPER_LS"],"involvedTables":[{"table":"zp_request","columns":["zp_request_id","zp_connection_id","zp_customer_name","zp_cust_zip"]},{"table":"zp_ui_data","columns":["zp_request_id","zp_action_id"]},{"table":"zp_ui_data_history","columns":["zp_request_id","zp_action_id","created_date"]}],"expectedOutputs":["validation state","queue progression"]}',
   'Use this flow when the question is about validation, queue progression, or whether the request should move forward.',
   TRUE, NOW()),
  ('zapper.central.disconnect.submit', 'Zapper Disconnect Submit Flow', 'ZAPPER_CENTRAL', 'SYNC',
   'Submit flow that sends the disconnect from the central orchestrator into the order domain.',
   '{"calls":["ZAPPER_ORDER_SERVICE"],"involvedIds":["zp_request_id","zp_connection_id","DON","zpDisconnectId"],"expectedOutputs":["disconnect order","downstream checks","disconnect identifier"]}',
   'Use this flow when the issue is about submit success, DON generation, or missing zpDisconnectId.',
   TRUE, NOW())
ON CONFLICT (api_code) DO UPDATE
SET
  api_name = EXCLUDED.api_name,
  system_code = EXCLUDED.system_code,
  flow_type = EXCLUDED.flow_type,
  description = EXCLUDED.description,
  metadata_json = EXCLUDED.metadata_json,
  llm_hint = EXCLUDED.llm_hint,
  enabled = EXCLUDED.enabled;

INSERT INTO ce_mcp_system_relation (from_system_code, relation_type, to_system_code, sequence_no, description, enabled)
VALUES
  ('ZAPPER_UI', 'CALLS', 'ZAPPER_CENTRAL', 1, 'UI sends operator requests into the central orchestration application.', TRUE),
  ('ZAPPER_CENTRAL', 'VALIDATES_WITH', 'ZAPPER_INV', 2, 'Nightly processing validates that inventory records exist.', TRUE),
  ('ZAPPER_CENTRAL', 'VALIDATES_WITH', 'ZAPPER_LS', 3, 'Location details are checked before workflow progression.', TRUE),
  ('ZAPPER_CENTRAL', 'SUBMITS_TO', 'ZAPPER_ORDER_SERVICE', 4, 'ASO submit sends the disconnect into the order domain.', TRUE),
  ('ZAPPER_ORDER_SERVICE', 'PROPAGATES_TO', 'ZAPPER_BILLBANK', 5, 'Generated disconnect IDs should become visible in billing.', TRUE)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5) Physical schema discovery
-- ---------------------------------------------------------------------------
-- No ce_mcp_db_object / ce_mcp_db_column / ce_mcp_db_join_path seed rows are
-- inserted here by default.
--
-- Runtime behavior:
--   1) ConvEngine introspects the live Postgres schema through JDBC metadata.
--   2) It auto-discovers tables, columns, primary keys, and foreign-key joins.
--   3) These three DBKG tables are now optional semantic overlays only.
--
-- Use those tables only when you need to:
--   - map a table to a business entity/system
--   - add synonyms, metadata_json, or llm_hint
--   - override an auto-discovered join with a preferred business join
--
-- Example optional overlay rows (uncomment and tailor per project):
-- INSERT INTO ce_mcp_db_object (object_name, object_type, system_code, entity_code, access_mode, description, metadata_json, llm_hint, enabled, created_at)
-- VALUES ('some_table', 'TABLE', 'YOUR_SYSTEM', 'YOUR_ENTITY', 'READ_ONLY',
--         'Short semantic description.',
--         '{"useCases":["triage","audit"],"importantColumns":["id","status_code"]}',
--         'Use this table first when the issue is about triage.',
--         TRUE, NOW());
--
-- INSERT INTO ce_mcp_db_column (object_name, column_name, semantic_name, synonyms, description, metadata_json, llm_hint, enabled)
-- VALUES ('some_table', 'status_code', 'workflow_status', 'status,action code',
--         'Business status field used in triage.',
--         '{"dictionary":"YOUR_STATUS_DICT"}',
--         'This is the primary state field for this table.',
--         TRUE);
--
-- INSERT INTO ce_mcp_db_join_path (join_name, left_object_name, right_object_name, join_type, join_sql_fragment, business_reason, metadata_json, llm_hint, confidence_score, enabled)
-- VALUES ('some_table_to_other_table', 'some_table', 'other_table', 'INNER',
--         'some_table.id = other_table.some_id',
--         'Preferred business join for audit scenarios.',
--         '{"preferredFor":["audit","reconciliation"]}',
--         'Prefer this join when both tables appear in the same question.',
--         1.00, TRUE);

INSERT INTO ce_mcp_status_dictionary (dictionary_name, field_name, code_value, code_label, business_meaning, synonyms, enabled)
VALUES
  ('ZP_UI_ACTION', 'zp_action_id', '100', 'REVIEW', 'ASR is reviewing the request.', 'review,under review', TRUE),
  ('ZP_UI_ACTION', 'zp_action_id', '200', 'ASSIGNED', 'ASR has assigned or taken ownership of the request.', 'assign,assigned', TRUE),
  ('ZP_UI_ACTION', 'zp_action_id', '300', 'SIGNOFF', 'ASR validation is complete and ready for handoff.', 'signoff,approved', TRUE),
  ('ZP_UI_ACTION', 'zp_action_id', '400', 'REJECTED', 'ASR rejected the request during validation.', 'reject,rejected', TRUE),
  ('ZP_UI_ACTION', 'zp_action_id', '500', 'SUBMITTED', 'ASO submitted the request downstream.', 'submit,submitted', TRUE)
ON CONFLICT (dictionary_name, field_name, code_value) DO UPDATE
SET
  code_label = EXCLUDED.code_label,
  business_meaning = EXCLUDED.business_meaning,
  synonyms = EXCLUDED.synonyms,
  enabled = EXCLUDED.enabled;

INSERT INTO ce_mcp_id_lineage (lineage_code, source_system_code, source_object_name, source_column_name, target_system_code, target_object_name, target_column_name, transform_rule, description, enabled)
VALUES
  ('request_to_ui', 'ZAPPER_CENTRAL', 'zp_request', 'zp_request_id', 'ZAPPER_CENTRAL', 'zp_ui_data', 'zp_request_id',
   'IDENTITY', 'The request identifier flows directly into the current UI row.', TRUE),
  ('request_to_ui_history', 'ZAPPER_CENTRAL', 'zp_request', 'zp_request_id', 'ZAPPER_CENTRAL', 'zp_ui_data_history', 'zp_request_id',
   'IDENTITY', 'The request identifier flows directly into the UI history rows.', TRUE),
  ('zapper_disconnect_to_billbank', 'ZAPPER_ORDER_SERVICE', NULL, 'zpDisconnectId', 'ZAPPER_BILLBANK', NULL, 'zpDisconnectId',
   'IDENTITY', 'The disconnect identifier should propagate from order service to billing.', TRUE)
ON CONFLICT (lineage_code) DO UPDATE
SET
  source_system_code = EXCLUDED.source_system_code,
  source_object_name = EXCLUDED.source_object_name,
  source_column_name = EXCLUDED.source_column_name,
  target_system_code = EXCLUDED.target_system_code,
  target_object_name = EXCLUDED.target_object_name,
  target_column_name = EXCLUDED.target_column_name,
  transform_rule = EXCLUDED.transform_rule,
  description = EXCLUDED.description,
  enabled = EXCLUDED.enabled;

-- ---------------------------------------------------------------------------
-- 6) Playbooks
-- ---------------------------------------------------------------------------
INSERT INTO ce_mcp_playbook (playbook_code, case_code, playbook_name, description, entry_strategy, priority, enabled, created_at)
VALUES
  ('ASR_ASSIGN_REJECT_AUDIT', 'SERVICE_DISCONNECT', 'ASR Assign To Reject Audit',
   'Find requests that were assigned by ASR and later rejected in a recent time window.', 'TOP_SCORE', 10, TRUE, NOW()),
  ('ASR_INVENTORY_NOT_FOUND', 'INVENTORY_MISMATCH', 'ASR Inventory Not Found',
   'Trace a request and determine whether the inventory mismatch is due to missing request lineage or missing consumer inventory data.', 'TOP_SCORE', 20, TRUE, NOW()),
  ('ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'DISCONNECT_SUBMIT_FAILURE', 'ASO Submitted But No zpDisconnectId After 24 Hours',
   'Trace submit success versus downstream disconnect ID creation and classify the break point.', 'TOP_SCORE', 30, TRUE, NOW()),
  ('ZPOS_BILLBANK_GAP', 'BILLING_RECONCILIATION', 'ZapperOS To BillBank Gap',
   'Detect when disconnect identifiers exist in the order domain but are not visible in billing.', 'TOP_SCORE', 40, TRUE, NOW())
ON CONFLICT (playbook_code) DO UPDATE
SET
  case_code = EXCLUDED.case_code,
  playbook_name = EXCLUDED.playbook_name,
  description = EXCLUDED.description,
  entry_strategy = EXCLUDED.entry_strategy,
  priority = EXCLUDED.priority,
  enabled = EXCLUDED.enabled;

INSERT INTO ce_mcp_playbook_signal (playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES
  ('ASR_ASSIGN_REJECT_AUDIT', 'KEYWORD', 'CONTAINS', 'assigned', 25.00, TRUE, TRUE, 'Looks for assigned state wording.'),
  ('ASR_ASSIGN_REJECT_AUDIT', 'KEYWORD', 'CONTAINS', 'rejected', 25.00, TRUE, TRUE, 'Looks for rejected state wording.'),
  ('ASR_ASSIGN_REJECT_AUDIT', 'KEYWORD', 'CONTAINS', '24 hours', 15.00, FALSE, TRUE, 'Looks for a recent time window.'),
  ('ASR_ASSIGN_REJECT_AUDIT', 'KEYWORD', 'CONTAINS', 'ASR', 10.00, FALSE, TRUE, 'Ties the question to ASR operations.'),
  ('ASR_INVENTORY_NOT_FOUND', 'KEYWORD', 'CONTAINS', 'inventory not found', 40.00, TRUE, TRUE, 'Primary symptom phrase.'),
  ('ASR_INVENTORY_NOT_FOUND', 'KEYWORD', 'CONTAINS', 'Irving', 5.00, FALSE, TRUE, 'Example location wording.'),
  ('ASR_INVENTORY_NOT_FOUND', 'KEYWORD', 'CONTAINS', 'accLocId', 20.00, FALSE, TRUE, 'Direct inventory lookup identifier.'),
  ('ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'KEYWORD', 'CONTAINS', 'submitted', 20.00, TRUE, TRUE, 'Submit event wording.'),
  ('ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'KEYWORD', 'CONTAINS', 'zpDisconnectId', 35.00, TRUE, TRUE, 'Missing final disconnect ID wording.'),
  ('ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'KEYWORD', 'CONTAINS', '24', 10.00, FALSE, TRUE, 'Time threshold wording.'),
  ('ZPOS_BILLBANK_GAP', 'KEYWORD', 'CONTAINS', 'BillBank', 30.00, TRUE, TRUE, 'Billing system wording.'),
  ('ZPOS_BILLBANK_GAP', 'KEYWORD', 'CONTAINS', 'not in', 15.00, FALSE, TRUE, 'Gap/mismatch wording.'),
  ('ZPOS_BILLBANK_GAP', 'KEYWORD', 'CONTAINS', 'zpDisconnectId', 25.00, FALSE, TRUE, 'Reconciliation key wording.')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 7) Query templates
-- ---------------------------------------------------------------------------
INSERT INTO ce_mcp_query_template (
  query_code, playbook_code, executor_code, purpose, sql_template, required_params, optional_params,
  result_contract, safety_class, default_limit, enabled, description, created_at
)
VALUES
  (
    'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME',
    'ASR_INVENTORY_NOT_FOUND',
    'QUERY_TEMPLATE_EXECUTOR',
    'Find requests by request id, connection id, account, account-location, customer name, zip, or contact number.',
     'SELECT r.zp_request_id, r.zp_connection_id, r.account_id, r.customer_id, r.location_id, r.acc_loc_id,
       r.zp_customer_name, r.zp_cust_zip, r.zp_contact_number, r.request_status, r.requested_provider, r.submitted_to_aso_at
 FROM zp_request r
 WHERE (:zp_request_id::varchar IS NULL OR r.zp_request_id = :zp_request_id)
   AND (:zp_connection_id::varchar IS NULL OR r.zp_connection_id = :zp_connection_id)
   AND (:account_id::varchar IS NULL OR r.account_id = :account_id)
   AND (:acc_loc_id::varchar IS NULL OR r.acc_loc_id = :acc_loc_id)
   AND (:zp_customer_name::varchar IS NULL OR UPPER(r.zp_customer_name) = UPPER(:zp_customer_name))
   AND (:zp_cust_zip::varchar IS NULL OR r.zp_cust_zip = :zp_cust_zip)
   AND (:zp_contact_number::varchar IS NULL OR r.zp_contact_number = :zp_contact_number)
 ORDER BY r.requested_at DESC, r.zp_request_id
 LIMIT :limit',
    '["limit"]',
    '["zp_request_id","zp_connection_id","account_id","acc_loc_id","zp_customer_name","zp_cust_zip","zp_contact_number"]',
    '{"primaryKeys":["zp_request_id"],"fields":["zp_request_id","zp_connection_id","account_id","customer_id","location_id","acc_loc_id","zp_customer_name","zp_cust_zip","zp_contact_number","request_status","requested_provider","submitted_to_aso_at"]}',
    'READ_ONLY_STRICT',
    50,
    TRUE,
    'Request lookup across the concrete Zapper transaction schema.',
    NOW()
  ),
  (
    'ZP_UI_CURRENT_BY_REQUEST',
    'ASR_INVENTORY_NOT_FOUND',
    'QUERY_TEMPLATE_EXECUTOR',
    'Find the current UI row for a request and expose the latest ASR owner, notes, action, and queue.',
    'SELECT u.zp_request_id, u.zp_asr_team_member_id, u.zp_asr_team_notes, u.zp_action_id, u.zp_queue_code, u.last_updated_at
FROM zp_ui_data u
WHERE u.zp_request_id = :zp_request_id
LIMIT :limit',
    '["zp_request_id","limit"]',
    '[]',
    '{"primaryKeys":["zp_request_id"],"fields":["zp_request_id","zp_asr_team_member_id","zp_asr_team_notes","zp_action_id","zp_queue_code","last_updated_at"]}',
    'READ_ONLY_STRICT',
    1,
    TRUE,
    'Current UI lookup using the live Zapper schema.',
    NOW()
  ),
  (
    'ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS',
    'ASR_ASSIGN_REJECT_AUDIT',
    'QUERY_TEMPLATE_EXECUTOR',
    'Find requests whose UI history moved from ASSIGNED to REJECTED in a rolling time window.',
    'SELECT h2.zp_request_id,
       h1.created_date AS assigned_at,
       h2.created_date AS rejected_at,
       h1.zp_asr_team_member_id AS assigned_by_member_id,
       h2.zp_asr_team_member_id AS rejected_by_member_id
FROM zp_ui_data_history h1
JOIN zp_ui_data_history h2
  ON h1.zp_request_id = h2.zp_request_id
 AND h1.created_date < h2.created_date
WHERE h1.zp_action_id = :from_action_id
  AND h2.zp_action_id = :to_action_id
  AND h2.created_date >= :from_ts
ORDER BY h2.created_date DESC
LIMIT :limit',
    '["from_action_id","to_action_id","from_ts","limit"]',
    '[]',
    '{"primaryKeys":["zp_request_id"],"fields":["zp_request_id","assigned_at","rejected_at","assigned_by_member_id","rejected_by_member_id"]}',
    'READ_ONLY_STRICT',
    100,
    TRUE,
    'Transition audit using the concrete Zapper UI history table.',
    NOW()
  ),
  (
    'ZP_INVENTORY_BY_REQUEST_LINKAGE',
    'ASR_INVENTORY_NOT_FOUND',
    'QUERY_TEMPLATE_EXECUTOR',
    'Check whether an inventory row exists for the request''s account-location and connection linkage.',
    'SELECT i.inventory_id, i.account_id, i.customer_id, i.acc_loc_id, i.zp_connection_id,
       i.zapper_id, i.inventory_status, i.provisioned_flag, i.inventory_sync_status, i.last_verified_at
FROM zp_inventory_service i
WHERE (:account_id IS NULL OR i.account_id = :account_id)
  AND (:acc_loc_id IS NULL OR i.acc_loc_id = :acc_loc_id)
  AND (:customer_id IS NULL OR i.customer_id = :customer_id)
  AND (:zp_connection_id IS NULL OR i.zp_connection_id = :zp_connection_id)
ORDER BY i.updated_at DESC
LIMIT :limit',
    '["limit"]',
    '["account_id","acc_loc_id","customer_id","zp_connection_id"]',
    '{"fields":["inventory_id","account_id","customer_id","acc_loc_id","zp_connection_id","zapper_id","inventory_status","provisioned_flag","inventory_sync_status","last_verified_at"]}',
    'READ_ONLY_STRICT',
    1,
    TRUE,
    'Real inventory lookup against zp_inventory_service.',
    NOW()
  ),
  (
    'ZP_DISCONNECT_CHAIN_BY_REQUEST',
    'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H',
    'QUERY_TEMPLATE_EXECUTOR',
    'Trace submitted disconnects older than the requested threshold and show missing disconnect id or failed downstream checks.',
    'SELECT o.zp_request_id, o.zp_connection_id, o.submitted_at AS submit_ts, o.don,
       o.downstream_status, o.zp_disconnect_id,
       (
         SELECT c.check_code
         FROM zp_order_downstream_check c
         WHERE c.don = o.don
           AND UPPER(c.check_status) <> ''PASS''
         ORDER BY c.checked_at DESC
         LIMIT 1
       ) AS failing_check_code,
       (
         SELECT c.failure_reason
         FROM zp_order_downstream_check c
         WHERE c.don = o.don
           AND UPPER(c.check_status) <> ''PASS''
         ORDER BY c.checked_at DESC
         LIMIT 1
       ) AS failing_reason
FROM zp_disconnect_order o
WHERE (:zp_request_id IS NULL OR o.zp_request_id = :zp_request_id)
  AND (:zp_connection_id IS NULL OR o.zp_connection_id = :zp_connection_id)
  AND o.submitted_at <= :from_ts
  AND (o.zp_disconnect_id IS NULL OR UPPER(o.downstream_status) <> ''COMPLETED'')
ORDER BY o.submitted_at ASC
LIMIT :limit',
    '["from_ts","limit"]',
    '["zp_request_id","zp_connection_id"]',
    '{"fields":["zp_request_id","zp_connection_id","submit_ts","don","downstream_status","zp_disconnect_id","failing_check_code","failing_reason"]}',
    'READ_ONLY_STRICT',
    1,
    TRUE,
    'Real disconnect-chain trace against zp_disconnect_order and zp_order_downstream_check.',
    NOW()
  ),
  (
    'ZP_BILLBANK_RECON_GAPS',
    'ZPOS_BILLBANK_GAP',
    'QUERY_TEMPLATE_EXECUTOR',
    'Find order-side disconnect ids that should exist in BillBank but are missing there.',
    'SELECT o.zp_request_id, o.don, o.zp_disconnect_id, o.zapper_id, o.submitted_at,
       ''MISSING_IN_BILLBANK'' AS billbank_status
FROM zp_disconnect_order o
LEFT JOIN zp_billbank_record b
  ON o.zp_disconnect_id = b.zp_disconnect_id
WHERE o.zp_disconnect_id IS NOT NULL
  AND b.billbank_id IS NULL
  AND (:zpDisconnectId IS NULL OR o.zp_disconnect_id = :zpDisconnectId)
ORDER BY o.submitted_at DESC
LIMIT :limit',
    '["limit"]',
    '["limit"]',
    '{"fields":["zp_request_id","don","zp_disconnect_id","zapper_id","submitted_at","billbank_status"]}',
    'READ_ONLY_STRICT',
    50,
    TRUE,
    'Real reconciliation query against zp_disconnect_order and zp_billbank_record.',
    NOW()
  )
ON CONFLICT (query_code) DO UPDATE
SET
  playbook_code = EXCLUDED.playbook_code,
  executor_code = EXCLUDED.executor_code,
  purpose = EXCLUDED.purpose,
  sql_template = EXCLUDED.sql_template,
  required_params = EXCLUDED.required_params,
  optional_params = EXCLUDED.optional_params,
  result_contract = EXCLUDED.result_contract,
  safety_class = EXCLUDED.safety_class,
  default_limit = EXCLUDED.default_limit,
  enabled = EXCLUDED.enabled,
  description = EXCLUDED.description;

INSERT INTO ce_mcp_query_param_rule (
  query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled
)
VALUES
  ('ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'limit', 'DEFAULT', 'DEFAULT_LIMIT', '50', TRUE, 'TO_INTEGER', 'Default request lookup limit.', TRUE),
  ('ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'zp_request_id', 'CASE_CONTEXT', 'knownIds.zp_request_id', NULL, FALSE, NULL, 'Optional request id from resolved case context.', TRUE),
  ('ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'zp_connection_id', 'CASE_CONTEXT', 'knownIds.zp_connection_id', NULL, FALSE, NULL, 'Optional connection id from resolved case context.', TRUE),
  ('ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'account_id', 'CASE_CONTEXT', 'knownIds.accountId', NULL, FALSE, NULL, 'Optional account id from resolved case context or direct args.', TRUE),
  ('ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'acc_loc_id', 'CASE_CONTEXT', 'knownIds.accLocId', NULL, FALSE, NULL, 'Optional account-location id from resolved case context or direct args.', TRUE),
  ('ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'zp_customer_name', 'CASE_CONTEXT', 'customerName', NULL, FALSE, 'TRIM', 'Optional customer name from resolved case context.', TRUE),
  ('ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'zp_cust_zip', 'CASE_CONTEXT', 'customerZip', NULL, FALSE, NULL, 'Optional zip code from resolved case context.', TRUE),
  ('ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'zp_contact_number', 'CASE_CONTEXT', 'contactNumber', NULL, FALSE, NULL, 'Optional contact number from resolved case context.', TRUE),

  ('ZP_UI_CURRENT_BY_REQUEST', 'zp_request_id', 'PREV_STEP_OUTPUT', 'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME[0].zp_request_id', NULL, TRUE, NULL, 'Use the first resolved request id.', TRUE),
  ('ZP_UI_CURRENT_BY_REQUEST', 'limit', 'DEFAULT', 'DEFAULT_LIMIT', '1', TRUE, 'TO_INTEGER', 'Only one current UI row is expected.', TRUE),

  ('ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS', 'from_action_id', 'STATUS_DICTIONARY', 'ZP_UI_ACTION.zp_action_id.ASSIGNED', '200', TRUE, NULL, 'Resolve ASSIGNED action code.', TRUE),
  ('ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS', 'to_action_id', 'STATUS_DICTIONARY', 'ZP_UI_ACTION.zp_action_id.REJECTED', '400', TRUE, NULL, 'Resolve REJECTED action code.', TRUE),
  ('ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS', 'from_ts', 'DERIVED_CONTEXT', 'timeWindow.fromTs', NULL, TRUE, NULL, 'Lower bound derived from the rolling time window.', TRUE),
  ('ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS', 'limit', 'DEFAULT', 'DEFAULT_LIMIT', '100', TRUE, 'TO_INTEGER', 'Default transition audit limit.', TRUE),

  ('ZP_INVENTORY_BY_REQUEST_LINKAGE', 'account_id', 'PREV_STEP_OUTPUT', 'LOOKUP_REQUEST[0].account_id', NULL, FALSE, NULL, 'Account id from the resolved request.', TRUE),
  ('ZP_INVENTORY_BY_REQUEST_LINKAGE', 'acc_loc_id', 'PREV_STEP_OUTPUT', 'LOOKUP_REQUEST[0].acc_loc_id', NULL, FALSE, NULL, 'Account-location id from the resolved request.', TRUE),
  ('ZP_INVENTORY_BY_REQUEST_LINKAGE', 'customer_id', 'PREV_STEP_OUTPUT', 'LOOKUP_REQUEST[0].customer_id', NULL, FALSE, NULL, 'Customer id from the resolved request.', TRUE),
  ('ZP_INVENTORY_BY_REQUEST_LINKAGE', 'zp_connection_id', 'PREV_STEP_OUTPUT', 'LOOKUP_REQUEST[0].zp_connection_id', NULL, FALSE, NULL, 'Connection id from the resolved request.', TRUE),
  ('ZP_INVENTORY_BY_REQUEST_LINKAGE', 'limit', 'DEFAULT', 'DEFAULT_LIMIT', '1', TRUE, 'TO_INTEGER', 'Inventory lookup limit.', TRUE),

  ('ZP_DISCONNECT_CHAIN_BY_REQUEST', 'zp_request_id', 'CASE_CONTEXT', 'knownIds.zp_request_id', NULL, FALSE, NULL, 'Optional request identifier if available.', TRUE),
  ('ZP_DISCONNECT_CHAIN_BY_REQUEST', 'zp_connection_id', 'CASE_CONTEXT', 'knownIds.zp_connection_id', NULL, FALSE, NULL, 'Optional connection identifier if available.', TRUE),
  ('ZP_DISCONNECT_CHAIN_BY_REQUEST', 'from_ts', 'DERIVED_CONTEXT', 'timeWindow.fromTs', NULL, TRUE, NULL, 'Threshold timestamp derived from the rolling time window.', TRUE),
  ('ZP_DISCONNECT_CHAIN_BY_REQUEST', 'limit', 'DEFAULT', 'DEFAULT_LIMIT', '25', TRUE, 'TO_INTEGER', 'Disconnect chain trace limit.', TRUE),

  ('ZP_BILLBANK_RECON_GAPS', 'zpDisconnectId', 'CASE_CONTEXT', 'knownIds.zpDisconnectId', NULL, FALSE, NULL, 'Optional disconnect identifier when explicitly provided.', TRUE),
  ('ZP_BILLBANK_RECON_GAPS', 'limit', 'DEFAULT', 'DEFAULT_LIMIT', '50', TRUE, 'TO_INTEGER', 'Reconciliation gap limit.', TRUE)
ON CONFLICT (query_code, param_name) DO UPDATE
SET
  source_type = EXCLUDED.source_type,
  source_key = EXCLUDED.source_key,
  default_value = EXCLUDED.default_value,
  required_flag = EXCLUDED.required_flag,
  transform_rule = EXCLUDED.transform_rule,
  description = EXCLUDED.description,
  enabled = EXCLUDED.enabled;

-- ---------------------------------------------------------------------------
-- 8) Playbook steps
-- ---------------------------------------------------------------------------
INSERT INTO ce_mcp_playbook_step (
  playbook_code, step_code, step_type, executor_code, template_code, input_contract,
  output_contract, config_json, sequence_no, halt_on_error, enabled
)
VALUES
  ('ASR_ASSIGN_REJECT_AUDIT', 'RESOLVE_TIME_WINDOW', 'DERIVED_FIELD', 'TIME_WINDOW_DERIVER', NULL,
   '{"hours":"integer"}',
   '{"fromTs":"timestamp"}',
   '{"hours":24,"isStart":true}',
   10, TRUE, TRUE),
  ('ASR_ASSIGN_REJECT_AUDIT', 'LOOKUP_ASSIGN_REJECT', 'SQL', 'QUERY_TEMPLATE_EXECUTOR', 'ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS',
   '{"from_action_id":"string","to_action_id":"string","from_ts":"timestamp","limit":"integer"}',
   '{"rows":"array"}',
   '{"queryCode":"ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS"}',
   20, TRUE, TRUE),
  ('ASR_ASSIGN_REJECT_AUDIT', 'SUMMARIZE_AUDIT', 'SUMMARY', 'SUMMARY_RENDERER', NULL,
   '{"rows":"array"}',
   '{"summary":"string"}',
   '{"summaryStyle":"transition_audit"}',
   30, TRUE, TRUE),

  ('ASR_INVENTORY_NOT_FOUND', 'LOOKUP_REQUEST', 'SQL', 'QUERY_TEMPLATE_EXECUTOR', 'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME',
   '{"search":"object"}',
   '{"rows":"array"}',
   '{"queryCode":"ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME","isStart":true}',
   10, TRUE, TRUE),
  ('ASR_INVENTORY_NOT_FOUND', 'LOOKUP_CURRENT_UI', 'SQL', 'QUERY_TEMPLATE_EXECUTOR', 'ZP_UI_CURRENT_BY_REQUEST',
   '{"zp_request_id":"string"}',
   '{"rows":"array"}',
   '{"queryCode":"ZP_UI_CURRENT_BY_REQUEST"}',
   20, TRUE, TRUE),
  ('ASR_INVENTORY_NOT_FOUND', 'CHECK_INVENTORY_LINKAGE', 'SQL', 'QUERY_TEMPLATE_EXECUTOR', 'ZP_INVENTORY_BY_REQUEST_LINKAGE',
   '{"accountId":"string"}',
   '{"rows":"array"}',
   '{"queryCode":"ZP_INVENTORY_BY_REQUEST_LINKAGE"}',
   30, FALSE, TRUE),
  ('ASR_INVENTORY_NOT_FOUND', 'SUMMARIZE_INVENTORY', 'SUMMARY', 'SUMMARY_RENDERER', NULL,
   '{"requestRows":"array","uiRows":"array","inventoryRows":"array"}',
   '{"summary":"string"}',
   '{"summaryStyle":"inventory_gap"}',
   40, TRUE, TRUE),

  ('ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'RESOLVE_TIME_WINDOW', 'DERIVED_FIELD', 'TIME_WINDOW_DERIVER', NULL,
   '{"hours":"integer"}',
   '{"fromTs":"timestamp"}',
   '{"hours":24,"isStart":true}',
   10, TRUE, TRUE),
  ('ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'CHECK_DISCONNECT_CHAIN', 'SQL', 'QUERY_TEMPLATE_EXECUTOR', 'ZP_DISCONNECT_CHAIN_BY_REQUEST',
   '{"zp_request_id":"string"}',
   '{"rows":"array"}',
   '{"queryCode":"ZP_DISCONNECT_CHAIN_BY_REQUEST"}',
   20, FALSE, TRUE),
  ('ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'SUMMARIZE_SUBMIT_FAILURE', 'SUMMARY', 'SUMMARY_RENDERER', NULL,
   '{"rows":"array"}',
   '{"summary":"string"}',
   '{"summaryStyle":"submit_failure"}',
   30, TRUE, TRUE),

  ('ZPOS_BILLBANK_GAP', 'CHECK_PROPAGATION_GAP', 'SQL', 'QUERY_TEMPLATE_EXECUTOR', 'ZP_BILLBANK_RECON_GAPS',
   '{"zpDisconnectId":"string"}',
   '{"rows":"array"}',
   '{"queryCode":"ZP_BILLBANK_RECON_GAPS","isStart":true}',
   10, FALSE, TRUE),
  ('ZPOS_BILLBANK_GAP', 'SUMMARIZE_BILLBANK_GAP', 'SUMMARY', 'SUMMARY_RENDERER', NULL,
   '{"rows":"array"}',
   '{"summary":"string"}',
   '{"summaryStyle":"reconciliation_gap"}',
   20, TRUE, TRUE)
ON CONFLICT (playbook_code, step_code) DO UPDATE
SET
  step_type = EXCLUDED.step_type,
  executor_code = EXCLUDED.executor_code,
  template_code = EXCLUDED.template_code,
  input_contract = EXCLUDED.input_contract,
  output_contract = EXCLUDED.output_contract,
  config_json = EXCLUDED.config_json,
  sequence_no = EXCLUDED.sequence_no,
  halt_on_error = EXCLUDED.halt_on_error,
  enabled = EXCLUDED.enabled;

INSERT INTO ce_mcp_playbook_transition (
  playbook_code, from_step_code, outcome_code, to_step_code, condition_expr, priority, enabled
)
VALUES
  ('ASR_ASSIGN_REJECT_AUDIT', 'RESOLVE_TIME_WINDOW', 'SUCCESS', 'LOOKUP_ASSIGN_REJECT', 'true', 10, TRUE),
  ('ASR_ASSIGN_REJECT_AUDIT', 'LOOKUP_ASSIGN_REJECT', 'SUCCESS', 'SUMMARIZE_AUDIT', 'true', 20, TRUE),
  ('ASR_INVENTORY_NOT_FOUND', 'LOOKUP_REQUEST', 'SUCCESS', 'LOOKUP_CURRENT_UI', 'true', 10, TRUE),
  ('ASR_INVENTORY_NOT_FOUND', 'LOOKUP_CURRENT_UI', 'SUCCESS', 'CHECK_INVENTORY_LINKAGE', 'true', 20, TRUE),
  ('ASR_INVENTORY_NOT_FOUND', 'CHECK_INVENTORY_LINKAGE', 'SUCCESS', 'SUMMARIZE_INVENTORY', 'true', 30, TRUE),
  ('ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'RESOLVE_TIME_WINDOW', 'SUCCESS', 'CHECK_DISCONNECT_CHAIN', 'true', 10, TRUE),
  ('ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'CHECK_DISCONNECT_CHAIN', 'SUCCESS', 'SUMMARIZE_SUBMIT_FAILURE', 'true', 20, TRUE),
  ('ZPOS_BILLBANK_GAP', 'CHECK_PROPAGATION_GAP', 'SUCCESS', 'SUMMARIZE_BILLBANK_GAP', 'true', 10, TRUE)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 9) Final diagnosis rules
-- ---------------------------------------------------------------------------
INSERT INTO ce_mcp_outcome_rule (
  playbook_code, outcome_code, condition_expr, severity, explanation_template, recommended_next_action, priority, enabled
)
VALUES
  (
    'ASR_ASSIGN_REJECT_AUDIT',
    'FOUND_TRANSITIONS',
    'rowCount > 0 AND placeholderSkipped = false',
    'INFO',
    'Found requests that moved from ASSIGNED to REJECTED in the requested window. Use the returned request IDs and member IDs for operator follow-up.',
    'Review the returned request IDs and compare ASR notes for repeated rejection patterns.',
    10,
    TRUE
  ),
  (
    'ASR_ASSIGN_REJECT_AUDIT',
    'NO_TRANSITIONS_FOUND',
    'rowCount = 0 OR placeholderSkipped = true',
    'INFO',
    'No requests were found moving from ASSIGNED to REJECTED in the requested window.',
    'Broaden the time window or verify the action-code dictionary values.',
    20,
    TRUE
  ),
  (
    'ASR_INVENTORY_NOT_FOUND',
    'REQUEST_NOT_FOUND',
    'requestRowCount = 0',
    'WARN',
    'The request could not be found in zp_request using the provided identifiers, so the failure is likely before ASR inventory validation.',
    'Verify the request/account/location identifiers captured by the operator.',
    10,
    TRUE
  ),
  (
    'ASR_INVENTORY_NOT_FOUND',
    'INVENTORY_PRESENT',
    'requestRowCount > 0 AND rowCount > 0 AND placeholderSkipped = false',
    'INFO',
    'Inventory exists for the resolved request linkage, so the issue is not a missing inventory row. Review the current UI state and notes for a validation or business-rule rejection instead.',
    'Inspect zp_ui_data and the returned inventory row to see whether the issue is a rules failure rather than an inventory gap.',
    20,
    TRUE
  ),
  (
    'ASR_INVENTORY_NOT_FOUND',
    'INVENTORY_MISSING',
    'requestRowCount > 0 AND rowCount = 0',
    'WARN',
    'The request exists but no matching inventory row was found for the resolved account-location and connection linkage. The likely break is in provisioning or inventory synchronization.',
    'Check zp_connection creation history and compare it to zp_inventory_service sync timestamps.',
    30,
    TRUE
  ),
  (
    'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H',
    'DOWNSTREAM_FAILURE_FOUND',
    'rowCount > 0 AND placeholderSkipped = false',
    'WARN',
    'Found submitted disconnects older than the threshold that still have no final disconnect id or have failing downstream checks. Use the returned DON, downstream status, and failing check fields to isolate the break point.',
    'Inspect the returned failing_check_code and failure_reason, then recheck zp_order_downstream_check for the same DON.',
    10,
    TRUE
  ),
  (
    'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H',
    'NO_STUCK_SUBMISSIONS',
    'rowCount = 0',
    'INFO',
    'No submitted disconnects older than the configured threshold were found without a final disconnect id.',
    'Broaden the time window or pass a specific request id if a known issue is expected.',
    20,
    TRUE
  ),
  (
    'ZPOS_BILLBANK_GAP',
    'BILLBANK_GAPS_FOUND',
    'rowCount > 0 AND placeholderSkipped = false',
    'WARN',
    'Found disconnect ids that exist in the order domain but are still missing in BillBank. This points to a post-order propagation or billing-sync gap.',
    'Use the returned DON and zp_disconnect_id values to trace BillBank ingestion and notification events.',
    10,
    TRUE
  ),
  (
    'ZPOS_BILLBANK_GAP',
    'NO_BILLBANK_GAPS',
    'rowCount = 0',
    'INFO',
    'No order-side disconnect ids were found missing in BillBank for the current filter.',
    'If a specific disconnect id is expected, rerun the check with that id in the arguments.',
    20,
    TRUE
  )
ON CONFLICT DO NOTHING;
