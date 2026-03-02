INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(1, 'AgentIntentResolver', 'MIN_CONFIDENCE', '0.55', true, '2026-02-19 23:22:22.487');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(2, 'AgentIntentResolver', 'COLLISION_GAP_THRESHOLD', '0.20', true, '2026-02-19 23:22:22.487');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(3, 'IntentResolutionStep', 'STICKY_INTENT', 'true', true, '2026-02-19 23:22:22.487');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(4, 'McpPlanner', 'SYSTEM_PROMPT', 'You are an MCP planning agent. Decide CALL_TOOL or ANSWER. Be safe, conservative, and return JSON only.', true, '2026-02-19 23:22:22.487');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(5, 'McpPlanner', 'USER_PROMPT', 'User input:\n{{user_input}}\nContext:\n{{context}}\nTools:\n{{mcp_tools}}\nObservations:\n{{mcp_observations}}\nReturn strict planner JSON.', true, '2026-02-19 23:22:22.487');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(6, 'AgentIntentResolver', 'SYSTEM_PROMPT', 'You are an intent resolution agent for a conversational engine.
        You are a JSON generator. You must output valid JSON only. Do not include any explanations, greetings, or markdown formatting. Only return the JSON object.
                 Return JSON ONLY with fields:
                 {
                   "intent": "<INTENT_CODE_OR_NULL>",
                   "state": "INTENT_COLLISION | IDLE",
                   "confidence": 0.0,
                   "needsClarification": false,
                   "clarificationResolved": false,
                   "clarificationQuestion": "",
                   "intentScores": [{"intent":"<INTENT_CODE>","confidence":0.0}],
                   "followups": []
                 }
                CHAIN-OF-THOUGHT POLICY:
- Do NOT reveal chain-of-thought.
- Do NOT explain how you reached the answer.
- Summaries, reasoning, or internal thoughts are forbidden.
                 Rules:
CRITICAL OUTPUT RULES:
- DO NOT include reasoning, thoughts, or analysis.
- DO NOT use <think> tags or similar.
- Return ONLY valid JSON.
- intent MUST be the intent CODE, not an id or priority.
- confidence MUST be between 0.0 and 1.0.
- clarificationQuestion MUST be null when needsClarification=false.
                 - Score all plausible intents and return them in intentScores sorted by confidence descending.
                 - If top intents are close and ambiguous, set state to INTENT_COLLISION and needsClarification=true.
                 - For INTENT_COLLISION, add one follow-up disambiguation question in followups.
                 - If top intent is clear, set intent to best intent and confidence to best confidence.
                 - If user input is question-like (what/where/when/why/how/which/who/help/details/required/needed),
                   keep informational intents (like FAQ-style intents) in intentScores unless clearly impossible.
                 - When a domain/task intent and informational intent are both plausible for a question, keep both with close scores;
                   prefer INTENT_COLLISION instead of collapsing too early.
                 - Use only allowed intents.
                 - Do not hallucinate missing identifiers or facts.
                 - Keep state non-null when possible.', true, '2026-02-19 23:22:22.487');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(7, 'AgentIntentResolver', 'USER_PROMPT', ' Context:
                {{context}}

                Allowed intents:
                {{allowed_intents}}

                Potential intent collisions:
                {{intent_collision_candidates}}

                Current intent scores:
                {{intent_scores}}

                Previous clarification question (if any):
                {{pending_clarification}}

                User input:
                {{user_input}}

                Return JSON in the required schema only.', true, '2026-02-19 23:22:22.487');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(8, 'AgentIntentCollisionResolver', 'SYSTEM_PROMPT', 'You are a workflow assistant handling ambiguous intent collisions.
Use followups first when present.
Ask one concise disambiguation question.
If followups is empty, ask user to choose from top intents.', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(9, 'AgentIntentCollisionResolver', 'USER_PROMPT', '
User message:
{{user_input}}

Followups:
{{followups}}

Top intent scores:
{{intent_top3}}

Session:
{{session}}

Context:
{{context}}
', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(10, 'AgentIntentCollisionResolver', 'DERIVATION_HINT', 'When multiple intents have similar scores, derive a new intent to disambiguate.
                Consider followup questions, top intent scores, and conversation history.', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(11, 'DialogueActStep', 'SYSTEM_PROMPT', 'You are a dialogue-act classifier.
Return JSON only with:
{"dialogueAct":"AFFIRM|NEGATE|EDIT|RESET|QUESTION|NEW_REQUEST","confidence":0.0}', true, '2026-02-20 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(12, 'DialogueActStep', 'USER_PROMPT', 'User text:
%s', true, '2026-02-20 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(13, 'DialogueActStep', 'SCHEMA_PROMPT', '{
  "type":"object",
  "required":["dialogueAct","confidence"],
  "properties":{
    "dialogueAct":{"type":"string","enum":["AFFIRM","NEGATE","EDIT","RESET","QUESTION","NEW_REQUEST"]},
    "confidence":{"type":"number"}
  },
  "additionalProperties":false
}', true, '2026-02-20 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(14, 'DialogueActStep', 'QUERY_REWRITE_SYSTEM_PROMPT', ' You are a dialogue-act classifier and intelligent query search rewriter.
                        Using the conversation history, rewrite the user''s text into an explicit, standalone query that perfectly describes their intent without needing the conversation history context.
                        Also classify their dialogue act.
                        Return JSON only matching the exact schema.', true, '2026-02-20 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(15, 'DialogueActStep', 'QUERY_REWRITE_USER_PROMPT', '
 Conversation History:
 {{conversation_history}}

 User input:
 {{user_input}}
', true, '2026-02-20 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(16, 'DialogueActStep', 'QUERY_REWRITE_SCHEMA_JSON', '{
  "type":"object",
  "required":["dialogueAct","confidence","standaloneQuery"],
  "properties":{
    "dialogueAct":{"type":"string","enum":["AFFIRM","NEGATE","EDIT","RESET","QUESTION","NEW_REQUEST"]},
    "confidence":{"type":"number"},
    "standaloneQuery":{"type":"string"}
  },
  "additionalProperties":false
}', true, '2026-02-20 10:15:54.230');

INSERT INTO ce_intent
(intent_code, description, priority, enabled, created_at, display_name, llm_hint)
VALUES('GREETING', 'When user greets you', 15, true, '2026-02-26 01:57:06.990', 'GREETING', 'Reply with firm greetings');
INSERT INTO ce_intent
(intent_code, description, priority, enabled, created_at, display_name, llm_hint)
VALUES('UNKNOWN', 'Fallback intent', 999, true, '2026-02-26 01:57:06.990', 'Unknown', 'Fallback when no intent matches');
INSERT INTO ce_intent
(intent_code, description, priority, enabled, created_at, display_name, llm_hint)
VALUES('DBKG_DIAGNOSTICS', 'Routes a user into the Database Knowledge Graph (DBKG) diagnostic flow for Zapper production investigations.', 20, true, '2026-03-01 21:38:56.220', 'DBKG Diagnostics', 'Use when the user asks a production-debug question about disconnects, inventory mismatches, billing gaps, or Zapper system behavior.');


INSERT INTO ce_intent_classifier
(classifier_id, intent_code, state_code, rule_type, pattern, priority, enabled, description)
VALUES(9, 'GREETING', 'IDLE', 'REGEX', '(?i)\b(hi|hello|hey|howdy)\b', 15, true, 'GREETING regex matcher');
INSERT INTO ce_intent_classifier
(classifier_id, intent_code, state_code, rule_type, pattern, priority, enabled, description)
VALUES(11, 'DBKG_DIAGNOSTICS', 'ANALYZE', 'REGEX', '(?i).*(disconnect|inventory not found|billbank|zapper|zpdisconnectid|assigned|rejected|order service|billing gap).*', 10, true, 'Routes Zapper diagnostic questions into the Database Knowledge Graph (DBKG) flow.');



INSERT INTO ce_mcp_api_flow
(api_flow_id, api_code, api_name, system_code, flow_type, description, metadata_json, llm_hint, enabled, created_at)
VALUES(1, 'zapper.central.nightly.validation', 'ZapperCentral Nightly Validation', 'ZAPPER_CENTRAL', 'BATCH', 'Nightly batch flow that validates queued requests before they move deeper into the workflow.', '{"calls":["ZAPPER_INV","ZAPPER_LS"],"involvedTables":[{"table":"zp_request","columns":["zp_request_id","zp_connection_id","zp_customer_name","zp_cust_zip"]},{"table":"zp_ui_data","columns":["zp_request_id","zp_action_id"]},{"table":"zp_ui_data_history","columns":["zp_request_id","zp_action_id","created_date"]}],"expectedOutputs":["validation state","queue progression"]}', 'Use this flow when the question is about validation, queue progression, or whether the request should move forward.', true, '2026-03-01 21:27:09.604');
INSERT INTO ce_mcp_api_flow
(api_flow_id, api_code, api_name, system_code, flow_type, description, metadata_json, llm_hint, enabled, created_at)
VALUES(2, 'zapper.central.disconnect.submit', 'Zapper Disconnect Submit Flow', 'ZAPPER_CENTRAL', 'SYNC', 'Submit flow that sends the disconnect from the central orchestrator into the order domain.', '{"calls":["ZAPPER_ORDER_SERVICE"],"involvedIds":["zp_request_id","zp_connection_id","DON","zpDisconnectId"],"expectedOutputs":["disconnect order","downstream checks","disconnect identifier"]}', 'Use this flow when the issue is about submit success, DON generation, or missing zpDisconnectId.', true, '2026-03-01 21:27:09.604');


INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(1, 'SERVICE_DISCONNECT', 'KEYWORD', 'CONTAINS', 'disconnect', 50.00, false, true, 'Broad disconnect intent signal.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(2, 'SERVICE_DISCONNECT', 'KEYWORD', 'CONTAINS', 'ASR', 10.00, false, true, 'Mentions the ASR team.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(3, 'SERVICE_DISCONNECT', 'KEYWORD', 'CONTAINS', 'ASO', 10.00, false, true, 'Mentions the ASO team.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(4, 'SERVICE_DISCONNECT', 'KEYWORD', 'CONTAINS', 'ZapperCentral', 5.00, false, true, 'Mentions the central orchestration app.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(5, 'INVENTORY_MISMATCH', 'KEYWORD', 'CONTAINS', 'inventory not found', 60.00, true, true, 'Classic ASR inventory symptom.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(6, 'INVENTORY_MISMATCH', 'KEYWORD', 'CONTAINS', 'inventory', 25.00, false, true, 'Broad inventory signal.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(7, 'INVENTORY_MISMATCH', 'KEYWORD', 'CONTAINS', 'accLocId', 15.00, false, true, 'Key inventory lookup identifier.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(8, 'DISCONNECT_SUBMIT_FAILURE', 'KEYWORD', 'CONTAINS', 'zpDisconnectId', 50.00, true, true, 'Missing disconnect ID symptom.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(9, 'DISCONNECT_SUBMIT_FAILURE', 'KEYWORD', 'CONTAINS', '24h', 20.00, false, true, 'SLA breach wording.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(10, 'DISCONNECT_SUBMIT_FAILURE', 'KEYWORD', 'CONTAINS', '24hr', 20.00, false, true, 'Alternate SLA wording.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(11, 'DISCONNECT_SUBMIT_FAILURE', 'KEYWORD', 'CONTAINS', 'submitted', 15.00, false, true, 'Submit event wording.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(12, 'BILLING_RECONCILIATION', 'KEYWORD', 'CONTAINS', 'BillBank', 40.00, true, true, 'Billing system signal.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(13, 'BILLING_RECONCILIATION', 'KEYWORD', 'CONTAINS', 'reconciliation', 30.00, false, true, 'Explicit reconciliation wording.');
INSERT INTO ce_mcp_case_signal
(signal_id, case_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(14, 'BILLING_RECONCILIATION', 'KEYWORD', 'CONTAINS', 'not in BillBank', 35.00, false, true, 'Gap between source and billing.');



INSERT INTO ce_mcp_case_type
(case_code, case_name, description, intent_code, state_code, priority, enabled, created_at)
VALUES('SERVICE_DISCONNECT', 'Service Disconnect', 'Investigations related to disconnect lifecycle, ASR review, ASO submission, and downstream order processing.', 'ANY', 'ANY', 10, true, '2026-03-01 21:27:09.574');
INSERT INTO ce_mcp_case_type
(case_code, case_name, description, intent_code, state_code, priority, enabled, created_at)
VALUES('INVENTORY_MISMATCH', 'Inventory Mismatch', 'Investigations where the operator sees inventory not found or provisioning/inventory lineage mismatches.', 'ANY', 'ANY', 20, true, '2026-03-01 21:27:09.574');
INSERT INTO ce_mcp_case_type
(case_code, case_name, description, intent_code, state_code, priority, enabled, created_at)
VALUES('DISCONNECT_SUBMIT_FAILURE', 'Disconnect Submit Failure', 'Investigations where submit succeeded but downstream disconnect IDs or orders were not created in time.', 'ANY', 'ANY', 30, true, '2026-03-01 21:27:09.574');
INSERT INTO ce_mcp_case_type
(case_code, case_name, description, intent_code, state_code, priority, enabled, created_at)
VALUES('BILLING_RECONCILIATION', 'Billing Reconciliation', 'Investigations where disconnect/order identifiers are inconsistent between order and billing systems.', 'ANY', 'ANY', 40, true, '2026-03-01 21:27:09.574');


INSERT INTO ce_mcp_domain_entity
(entity_code, entity_name, description, synonyms, criticality, metadata_json, llm_hint, enabled, created_at)
VALUES('CUSTOMER', 'Customer', 'The end customer who owns one or more accounts.', 'customer,consumer,account holder', 'HIGH', '{"exampleRoles":["requester","account holder"]}', 'Use when the question is about who owns the service/account.', true, '2026-03-01 21:27:09.585');
INSERT INTO ce_mcp_domain_entity
(entity_code, entity_name, description, synonyms, criticality, metadata_json, llm_hint, enabled, created_at)
VALUES('ACCOUNT', 'Account', 'Commercial account maintained for a customer.', 'account,accountId', 'HIGH', '{"primaryIds":["accountId"]}', 'Represents the billing/service account anchor.', true, '2026-03-01 21:27:09.585');
INSERT INTO ce_mcp_domain_entity
(entity_code, entity_name, description, synonyms, criticality, metadata_json, llm_hint, enabled, created_at)
VALUES('SERVICE_LOCATION', 'Service Location', 'Physical location where electric service exists.', 'location,service location,locationId', 'HIGH', '{"primaryIds":["locationId"]}', 'Represents the physical service address or site.', true, '2026-03-01 21:27:09.585');
INSERT INTO ce_mcp_domain_entity
(entity_code, entity_name, description, synonyms, criticality, metadata_json, llm_hint, enabled, created_at)
VALUES('ACCOUNT_LOCATION', 'Account Location', 'Relationship between account and service location.', 'accLocId,account location,customerLocId', 'HIGH', '{"primaryIds":["accLocId","customerLocId"]}', 'Use when the user refers to account-location linkage identifiers.', true, '2026-03-01 21:27:09.585');
INSERT INTO ce_mcp_domain_entity
(entity_code, entity_name, description, synonyms, criticality, metadata_json, llm_hint, enabled, created_at)
VALUES('DISCONNECT_REQUEST', 'Disconnect Request', 'Operator request to disconnect service for an account/location.', 'disconnect request,request,zp_request_id', 'HIGH', '{"primaryIds":["zp_request_id"]}', 'Primary business request before downstream submission.', true, '2026-03-01 21:27:09.585');
INSERT INTO ce_mcp_domain_entity
(entity_code, entity_name, description, synonyms, criticality, metadata_json, llm_hint, enabled, created_at)
VALUES('ASR_ASSIGNMENT', 'ASR Assignment', 'ASR review and action state for a request.', 'assignment,review,reject,assign,zp_action_id', 'HIGH', '{"statusField":"zp_action_id"}', 'Use for request state transitions and operator actions.', true, '2026-03-01 21:27:09.585');
INSERT INTO ce_mcp_domain_entity
(entity_code, entity_name, description, synonyms, criticality, metadata_json, llm_hint, enabled, created_at)
VALUES('DISCONNECT_ORDER', 'Disconnect Order', 'Downstream order created when the disconnect is submitted.', 'DON,disconnect order,orderNumber', 'HIGH', '{"primaryIds":["DON","orderNumber"]}', 'Represents the downstream order entity.', true, '2026-03-01 21:27:09.585');
INSERT INTO ce_mcp_domain_entity
(entity_code, entity_name, description, synonyms, criticality, metadata_json, llm_hint, enabled, created_at)
VALUES('ZAPPER_SERVICE_ID', 'Zapper Service Identifier', 'Service identifier created during connection provisioning and reused during disconnect.', 'zapperId,service id', 'HIGH', '{"primaryIds":["zapperId"]}', 'Stable per-location service identifier across lifecycle.', true, '2026-03-01 21:27:09.585');
INSERT INTO ce_mcp_domain_entity
(entity_code, entity_name, description, synonyms, criticality, metadata_json, llm_hint, enabled, created_at)
VALUES('DISCONNECT_ID', 'Disconnect Identifier', 'Final disconnect identifier created after downstream checks succeed.', 'zpDisconnectId,disconnect id', 'HIGH', '{"primaryIds":["zpDisconnectId"]}', 'Final disconnect completion identifier.', true, '2026-03-01 21:27:09.585');
INSERT INTO ce_mcp_domain_entity
(entity_code, entity_name, description, synonyms, criticality, metadata_json, llm_hint, enabled, created_at)
VALUES('BILLING_RECORD', 'Billing Record', 'Billing and bill cease record tied to service/disconnect identifiers.', 'billing,billbank,bill cease', 'HIGH', '{"downstreamSystem":"billing"}', 'Represents billing-side visibility for disconnect state.', true, '2026-03-01 21:27:09.585');



INSERT INTO ce_mcp_domain_relation
(relation_id, from_entity_code, relation_type, to_entity_code, "cardinality", description, enabled)
VALUES(1, 'CUSTOMER', 'OWNS', 'ACCOUNT', 'ONE_TO_MANY', 'A customer may own multiple accounts.', true);
INSERT INTO ce_mcp_domain_relation
(relation_id, from_entity_code, relation_type, to_entity_code, "cardinality", description, enabled)
VALUES(2, 'ACCOUNT', 'SERVES', 'SERVICE_LOCATION', 'MANY_TO_MANY', 'Accounts can be tied to one or more service locations.', true);
INSERT INTO ce_mcp_domain_relation
(relation_id, from_entity_code, relation_type, to_entity_code, "cardinality", description, enabled)
VALUES(3, 'ACCOUNT', 'HAS', 'ACCOUNT_LOCATION', 'ONE_TO_MANY', 'Each account may have multiple account-location relationships.', true);
INSERT INTO ce_mcp_domain_relation
(relation_id, from_entity_code, relation_type, to_entity_code, "cardinality", description, enabled)
VALUES(4, 'ACCOUNT_LOCATION', 'GENERATES', 'DISCONNECT_REQUEST', 'ONE_TO_MANY', 'A disconnect request is raised against a specific account/location.', true);
INSERT INTO ce_mcp_domain_relation
(relation_id, from_entity_code, relation_type, to_entity_code, "cardinality", description, enabled)
VALUES(5, 'DISCONNECT_REQUEST', 'TRACKED_BY', 'ASR_ASSIGNMENT', 'ONE_TO_MANY', 'ASR actions record the operator state transitions.', true);
INSERT INTO ce_mcp_domain_relation
(relation_id, from_entity_code, relation_type, to_entity_code, "cardinality", description, enabled)
VALUES(6, 'DISCONNECT_REQUEST', 'GENERATES', 'DISCONNECT_ORDER', 'ZERO_TO_ONE', 'A valid submitted disconnect creates a downstream order.', true);
INSERT INTO ce_mcp_domain_relation
(relation_id, from_entity_code, relation_type, to_entity_code, "cardinality", description, enabled)
VALUES(7, 'DISCONNECT_ORDER', 'PRODUCES', 'DISCONNECT_ID', 'ZERO_TO_ONE', 'A successful downstream flow creates the final disconnect ID.', true);
INSERT INTO ce_mcp_domain_relation
(relation_id, from_entity_code, relation_type, to_entity_code, "cardinality", description, enabled)
VALUES(8, 'DISCONNECT_ID', 'SYNCS_TO', 'BILLING_RECORD', 'ONE_TO_ONE', 'The disconnect ID should be visible in billing after propagation.', true);



INSERT INTO ce_mcp_executor_template
(executor_code, executor_type, config_schema_json, description, enabled, created_at)
VALUES('CASE_SIGNAL_MATCHER', 'DECISION', '{"inputs":["userText"],"outputs":["caseCode","scoreBreakdown"],"notes":"Generic weighted matcher over ce_mcp_case_signal."}', 'Scores user language against case signals and returns ranked business cases.', true, '2026-03-01 21:27:09.570');
INSERT INTO ce_mcp_executor_template
(executor_code, executor_type, config_schema_json, description, enabled, created_at)
VALUES('PLAYBOOK_SIGNAL_MATCHER', 'DECISION', '{"inputs":["userText","caseCode"],"outputs":["playbookCode","scoreBreakdown"],"notes":"Generic weighted matcher over ce_mcp_playbook_signal."}', 'Scores user language against playbook signals under the selected case.', true, '2026-03-01 21:27:09.570');
INSERT INTO ce_mcp_executor_template
(executor_code, executor_type, config_schema_json, description, enabled, created_at)
VALUES('STATUS_LOOKUP', 'LOOKUP', '{"inputs":["dictionaryName","fieldName","labels"],"outputs":["resolvedCodes"],"notes":"Maps labels like ASSIGNED/REJECTED to code values."}', 'Resolves business-friendly status words into actual DB code values.', true, '2026-03-01 21:27:09.570');
INSERT INTO ce_mcp_executor_template
(executor_code, executor_type, config_schema_json, description, enabled, created_at)
VALUES('TIME_WINDOW_DERIVER', 'DERIVED_FIELD', '{"inputs":["hours"],"outputs":["fromTs"],"notes":"Computes a lower-bound timestamp from now minus N hours."}', 'Builds reusable rolling time-window inputs.', true, '2026-03-01 21:27:09.570');
INSERT INTO ce_mcp_executor_template
(executor_code, executor_type, config_schema_json, description, enabled, created_at)
VALUES('QUERY_TEMPLATE_EXECUTOR', 'SQL', '{"inputs":["queryCode","params"],"outputs":["rows"],"notes":"Executes approved SELECT-only SQL templates with bound params."}', 'Runs approved SQL templates from ce_mcp_query_template.', true, '2026-03-01 21:27:09.570');
INSERT INTO ce_mcp_executor_template
(executor_code, executor_type, config_schema_json, description, enabled, created_at)
VALUES('SUMMARY_RENDERER', 'SUMMARY', '{"inputs":["rows","context"],"outputs":["summary"],"notes":"Renders operator-facing findings from step outputs."}', 'Builds a final explanation from investigation outputs and outcome rules.', true, '2026-03-01 21:27:09.570');



INSERT INTO ce_mcp_id_lineage
(lineage_id, lineage_code, source_system_code, source_object_name, source_column_name, target_system_code, target_object_name, target_column_name, transform_rule, description, enabled)
VALUES(1, 'request_to_ui', 'ZAPPER_CENTRAL', 'zp_request', 'zp_request_id', 'ZAPPER_CENTRAL', 'zp_ui_data', 'zp_request_id', 'IDENTITY', 'The request identifier flows directly into the current UI row.', true);
INSERT INTO ce_mcp_id_lineage
(lineage_id, lineage_code, source_system_code, source_object_name, source_column_name, target_system_code, target_object_name, target_column_name, transform_rule, description, enabled)
VALUES(2, 'request_to_ui_history', 'ZAPPER_CENTRAL', 'zp_request', 'zp_request_id', 'ZAPPER_CENTRAL', 'zp_ui_data_history', 'zp_request_id', 'IDENTITY', 'The request identifier flows directly into the UI history rows.', true);
INSERT INTO ce_mcp_id_lineage
(lineage_id, lineage_code, source_system_code, source_object_name, source_column_name, target_system_code, target_object_name, target_column_name, transform_rule, description, enabled)
VALUES(3, 'zapper_disconnect_to_billbank', 'ZAPPER_ORDER_SERVICE', NULL, 'zpDisconnectId', 'ZAPPER_BILLBANK', NULL, 'zpDisconnectId', 'IDENTITY', 'The disconnect identifier should propagate from order service to billing.', true);

INSERT INTO ce_mcp_outcome_rule
(outcome_rule_id, playbook_code, outcome_code, condition_expr, severity, explanation_template, recommended_next_action, priority, enabled)
VALUES(1, 'ASR_ASSIGN_REJECT_AUDIT', 'FOUND_TRANSITIONS', 'rowCount > 0 AND placeholderSkipped = false', 'INFO', 'Found requests that moved from ASSIGNED to REJECTED in the requested window. Use the returned request IDs and member IDs for operator follow-up.', 'Review the returned request IDs and compare ASR notes for repeated rejection patterns.', 10, true);
INSERT INTO ce_mcp_outcome_rule
(outcome_rule_id, playbook_code, outcome_code, condition_expr, severity, explanation_template, recommended_next_action, priority, enabled)
VALUES(2, 'ASR_ASSIGN_REJECT_AUDIT', 'NO_TRANSITIONS_FOUND', 'rowCount = 0 OR placeholderSkipped = true', 'INFO', 'No requests were found moving from ASSIGNED to REJECTED in the requested window.', 'Broaden the time window or verify the action-code dictionary values.', 20, true);
INSERT INTO ce_mcp_outcome_rule
(outcome_rule_id, playbook_code, outcome_code, condition_expr, severity, explanation_template, recommended_next_action, priority, enabled)
VALUES(3, 'ASR_INVENTORY_NOT_FOUND', 'REQUEST_NOT_FOUND', 'requestRowCount = 0', 'WARN', 'The request could not be found in zp_request using the provided identifiers, so the failure is likely before ASR inventory validation.', 'Verify the request/account/location identifiers captured by the operator.', 10, true);
INSERT INTO ce_mcp_outcome_rule
(outcome_rule_id, playbook_code, outcome_code, condition_expr, severity, explanation_template, recommended_next_action, priority, enabled)
VALUES(4, 'ASR_INVENTORY_NOT_FOUND', 'INVENTORY_PRESENT', 'requestRowCount > 0 AND rowCount > 0 AND placeholderSkipped = false', 'INFO', 'Inventory exists for the resolved request linkage, so the issue is not a missing inventory row. Review the current UI state and notes for a validation or business-rule rejection instead.', 'Inspect zp_ui_data and the returned inventory row to see whether the issue is a rules failure rather than an inventory gap.', 20, true);
INSERT INTO ce_mcp_outcome_rule
(outcome_rule_id, playbook_code, outcome_code, condition_expr, severity, explanation_template, recommended_next_action, priority, enabled)
VALUES(5, 'ASR_INVENTORY_NOT_FOUND', 'INVENTORY_MISSING', 'requestRowCount > 0 AND rowCount = 0', 'WARN', 'The request exists but no matching inventory row was found for the resolved account-location and connection linkage. The likely break is in provisioning or inventory synchronization.', 'Check zp_connection creation history and compare it to zp_inventory_service sync timestamps.', 30, true);
INSERT INTO ce_mcp_outcome_rule
(outcome_rule_id, playbook_code, outcome_code, condition_expr, severity, explanation_template, recommended_next_action, priority, enabled)
VALUES(6, 'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'DOWNSTREAM_FAILURE_FOUND', 'rowCount > 0 AND placeholderSkipped = false', 'WARN', 'Found submitted disconnects older than the threshold that still have no final disconnect id or have failing downstream checks. Use the returned DON, downstream status, and failing check fields to isolate the break point.', 'Inspect the returned failing_check_code and failure_reason, then recheck zp_order_downstream_check for the same DON.', 10, true);
INSERT INTO ce_mcp_outcome_rule
(outcome_rule_id, playbook_code, outcome_code, condition_expr, severity, explanation_template, recommended_next_action, priority, enabled)
VALUES(7, 'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'NO_STUCK_SUBMISSIONS', 'rowCount = 0', 'INFO', 'No submitted disconnects older than the configured threshold were found without a final disconnect id.', 'Broaden the time window or pass a specific request id if a known issue is expected.', 20, true);
INSERT INTO ce_mcp_outcome_rule
(outcome_rule_id, playbook_code, outcome_code, condition_expr, severity, explanation_template, recommended_next_action, priority, enabled)
VALUES(8, 'ZPOS_BILLBANK_GAP', 'BILLBANK_GAPS_FOUND', 'rowCount > 0 AND placeholderSkipped = false', 'WARN', 'Found disconnect ids that exist in the order domain but are still missing in BillBank. This points to a post-order propagation or billing-sync gap.', 'Use the returned DON and zp_disconnect_id values to trace BillBank ingestion and notification events.', 10, true);
INSERT INTO ce_mcp_outcome_rule
(outcome_rule_id, playbook_code, outcome_code, condition_expr, severity, explanation_template, recommended_next_action, priority, enabled)
VALUES(9, 'ZPOS_BILLBANK_GAP', 'NO_BILLBANK_GAPS', 'rowCount = 0', 'INFO', 'No order-side disconnect ids were found missing in BillBank for the current filter.', 'If a specific disconnect id is expected, rerun the check with that id in the arguments.', 20, true);




INSERT INTO ce_mcp_planner
(planner_id, intent_code, state_code, system_prompt, user_prompt, enabled, created_at)
VALUES(5101, 'ORDER_DIAGNOSTICS', 'ANALYZE', 'You are an MCP planning agent for order diagnostics.\nTool order:\n1) mock.order.status\n2) mock.order.async.trace\n3) ANSWER with concise diagnosis from observations.\nReturn JSON only. Do not invent values.', 'User input:\n{{user_input}}\n\nContext JSON:\n{{context}}\n\nAvailable MCP tools:\n{{mcp_tools}}\n\nExisting MCP observations:\n{{mcp_observations}}\n\nReturn strict JSON:\n{\n  "action":"CALL_TOOL" | "ANSWER",\n  "tool_code":"<tool_code_or_null>",\n  "args":{},\n  "answer":"<text_or_null>"\n}', true, '2026-02-26 01:57:06.977');
INSERT INTO ce_mcp_planner
(planner_id, intent_code, state_code, system_prompt, user_prompt, enabled, created_at)
VALUES(5201, 'ANY', 'ANY', 'You are an MCP planning agent inside ConvEngine. Decide whether to CALL_TOOL or ANSWER. Be conservative, safe, and do not hallucinate missing data. Return JSON only.', 'User input:\n{{user_input}}\n\nContext JSON:\n{{context}}\n\nAvailable MCP tools:\n{{mcp_tools}}\n\nExisting MCP observations:\n{{mcp_observations}}\n\nReturn strict JSON:\n{\n  "action":"CALL_TOOL" | "ANSWER",\n  "tool_code":"<tool_code_or_null>",\n  "args":{},\n  "answer":"<text_or_null>"\n}', true, '2026-02-26 01:57:06.977');
INSERT INTO ce_mcp_planner
(planner_id, intent_code, state_code, system_prompt, user_prompt, enabled, created_at)
VALUES(6202, 'LOAN_APPLICATION', 'PROCESS_APPLICATION', 'You are an MCP planning agent for a loan application workflow.
You MUST follow tool order:
1) loan.credit.rating.check
2) If creditRating <= 750 => ANSWER reject
3) Else loan.credit.fraud.check
4) If flagged=true => ANSWER reject
5) Else loan.debt.credit.summary
6) If dti > 0.65 or availableCredit < requestedAmount*0.15 => ANSWER reject/manual-review
7) Else loan.application.submit
8) ANSWER with applicationId.
Return JSON only.', 'User input:
{{resolved_user_input}}

Context JSON:
{{context}}

Available MCP tools:
{{mcp_tools}}

Existing MCP observations:
{{mcp_observations}}

Return strict JSON:
{
  "action":"CALL_TOOL" | "ANSWER",
  "tool_code":"<tool_code_or_null>",
  "args":{},
  "answer":"<text_or_null>"
}', true, '2026-02-27 22:28:19.833');
INSERT INTO ce_mcp_planner
(planner_id, intent_code, state_code, system_prompt, user_prompt, enabled, created_at)
VALUES(5901, 'DBKG_DIAGNOSTICS', 'ANALYZE', 'You are an MCP planning agent for DB-backed knowledge-graph investigations.
You MUST follow this exact tool order and termination rule.

Tool order:
1) dbkg.case.resolve
2) dbkg.knowledge.lookup
3) dbkg.investigate.plan
4) dbkg.playbook.validate
5) If and only if valid=true AND canExecute=true, call dbkg.investigate.execute

Termination:
- After dbkg.investigate.execute, you MUST return action=ANSWER on the next turn.
- If validation fails, you MUST return action=ANSWER immediately with graphError or summary.
- Never call any tool after dbkg.investigate.execute.

Return JSON only. Do not invent data. If observations are sufficient, answer concisely.
', 'User input:
{{user_input}}

Effective user input:
{{resolved_user_input}}

Context JSON:
{{context}}

Available MCP tools:
{{mcp_tools}}

Existing MCP observations:
{{mcp_observations}}

Return strict JSON:
{
  "action":"CALL_TOOL" | "ANSWER",
  "tool_code":"<tool_code_or_null>",
  "args":{},
  "answer":"<text_or_null>"
}', true, '2026-03-01 21:27:09.567');



INSERT INTO ce_mcp_playbook
(playbook_code, case_code, playbook_name, description, entry_strategy, priority, enabled, created_at)
VALUES('ASR_ASSIGN_REJECT_AUDIT', 'SERVICE_DISCONNECT', 'ASR Assign To Reject Audit', 'Find requests that were assigned by ASR and later rejected in a recent time window.', 'TOP_SCORE', 10, true, '2026-03-01 21:27:09.623');
INSERT INTO ce_mcp_playbook
(playbook_code, case_code, playbook_name, description, entry_strategy, priority, enabled, created_at)
VALUES('ASR_INVENTORY_NOT_FOUND', 'INVENTORY_MISMATCH', 'ASR Inventory Not Found', 'Trace a request and determine whether the inventory mismatch is due to missing request lineage or missing consumer inventory data.', 'TOP_SCORE', 20, true, '2026-03-01 21:27:09.623');
INSERT INTO ce_mcp_playbook
(playbook_code, case_code, playbook_name, description, entry_strategy, priority, enabled, created_at)
VALUES('ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'DISCONNECT_SUBMIT_FAILURE', 'ASO Submitted But No zpDisconnectId After 24 Hours', 'Trace submit success versus downstream disconnect ID creation and classify the break point.', 'TOP_SCORE', 30, true, '2026-03-01 21:27:09.623');
INSERT INTO ce_mcp_playbook
(playbook_code, case_code, playbook_name, description, entry_strategy, priority, enabled, created_at)
VALUES('ZPOS_BILLBANK_GAP', 'BILLING_RECONCILIATION', 'ZapperOS To BillBank Gap', 'Detect when disconnect identifiers exist in the order domain but are not visible in billing.', 'TOP_SCORE', 40, true, '2026-03-01 21:27:09.623');



INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(1, 'ASR_ASSIGN_REJECT_AUDIT', 'KEYWORD', 'CONTAINS', 'assigned', 25.00, true, true, 'Looks for assigned state wording.');
INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(2, 'ASR_ASSIGN_REJECT_AUDIT', 'KEYWORD', 'CONTAINS', 'rejected', 25.00, true, true, 'Looks for rejected state wording.');
INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(3, 'ASR_ASSIGN_REJECT_AUDIT', 'KEYWORD', 'CONTAINS', '24 hours', 15.00, false, true, 'Looks for a recent time window.');
INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(4, 'ASR_ASSIGN_REJECT_AUDIT', 'KEYWORD', 'CONTAINS', 'ASR', 10.00, false, true, 'Ties the question to ASR operations.');
INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(5, 'ASR_INVENTORY_NOT_FOUND', 'KEYWORD', 'CONTAINS', 'inventory not found', 40.00, true, true, 'Primary symptom phrase.');
INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(6, 'ASR_INVENTORY_NOT_FOUND', 'KEYWORD', 'CONTAINS', 'Irving', 5.00, false, true, 'Example location wording.');
INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(7, 'ASR_INVENTORY_NOT_FOUND', 'KEYWORD', 'CONTAINS', 'accLocId', 20.00, false, true, 'Direct inventory lookup identifier.');
INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(8, 'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'KEYWORD', 'CONTAINS', 'submitted', 20.00, true, true, 'Submit event wording.');
INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(9, 'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'KEYWORD', 'CONTAINS', 'zpDisconnectId', 35.00, true, true, 'Missing final disconnect ID wording.');
INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(10, 'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'KEYWORD', 'CONTAINS', '24', 10.00, false, true, 'Time threshold wording.');
INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(11, 'ZPOS_BILLBANK_GAP', 'KEYWORD', 'CONTAINS', 'BillBank', 30.00, true, true, 'Billing system wording.');
INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(12, 'ZPOS_BILLBANK_GAP', 'KEYWORD', 'CONTAINS', 'not in', 15.00, false, true, 'Gap/mismatch wording.');
INSERT INTO ce_mcp_playbook_signal
(playbook_signal_id, playbook_code, signal_type, match_operator, match_value, weight, required_flag, enabled, description)
VALUES(13, 'ZPOS_BILLBANK_GAP', 'KEYWORD', 'CONTAINS', 'zpDisconnectId', 25.00, false, true, 'Reconciliation key wording.');


INSERT INTO ce_mcp_playbook_step
(step_id, playbook_code, step_code, step_type, executor_code, template_code, input_contract, output_contract, config_json, sequence_no, halt_on_error, enabled)
VALUES(1, 'ASR_ASSIGN_REJECT_AUDIT', 'RESOLVE_TIME_WINDOW', 'DERIVED_FIELD', 'TIME_WINDOW_DERIVER', NULL, '{"hours":"integer"}', '{"fromTs":"timestamp"}', '{"hours":24,"isStart":true}', 10, true, true);
INSERT INTO ce_mcp_playbook_step
(step_id, playbook_code, step_code, step_type, executor_code, template_code, input_contract, output_contract, config_json, sequence_no, halt_on_error, enabled)
VALUES(2, 'ASR_ASSIGN_REJECT_AUDIT', 'LOOKUP_ASSIGN_REJECT', 'SQL', 'QUERY_TEMPLATE_EXECUTOR', 'ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS', '{"from_action_id":"string","to_action_id":"string","from_ts":"timestamp","limit":"integer"}', '{"rows":"array"}', '{"queryCode":"ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS"}', 20, true, true);
INSERT INTO ce_mcp_playbook_step
(step_id, playbook_code, step_code, step_type, executor_code, template_code, input_contract, output_contract, config_json, sequence_no, halt_on_error, enabled)
VALUES(3, 'ASR_ASSIGN_REJECT_AUDIT', 'SUMMARIZE_AUDIT', 'SUMMARY', 'SUMMARY_RENDERER', NULL, '{"rows":"array"}', '{"summary":"string"}', '{"summaryStyle":"transition_audit"}', 30, true, true);
INSERT INTO ce_mcp_playbook_step
(step_id, playbook_code, step_code, step_type, executor_code, template_code, input_contract, output_contract, config_json, sequence_no, halt_on_error, enabled)
VALUES(4, 'ASR_INVENTORY_NOT_FOUND', 'LOOKUP_REQUEST', 'SQL', 'QUERY_TEMPLATE_EXECUTOR', 'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', '{"search":"object"}', '{"rows":"array"}', '{"queryCode":"ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME","isStart":true}', 10, true, true);
INSERT INTO ce_mcp_playbook_step
(step_id, playbook_code, step_code, step_type, executor_code, template_code, input_contract, output_contract, config_json, sequence_no, halt_on_error, enabled)
VALUES(5, 'ASR_INVENTORY_NOT_FOUND', 'LOOKUP_CURRENT_UI', 'SQL', 'QUERY_TEMPLATE_EXECUTOR', 'ZP_UI_CURRENT_BY_REQUEST', '{"zp_request_id":"string"}', '{"rows":"array"}', '{"queryCode":"ZP_UI_CURRENT_BY_REQUEST"}', 20, true, true);
INSERT INTO ce_mcp_playbook_step
(step_id, playbook_code, step_code, step_type, executor_code, template_code, input_contract, output_contract, config_json, sequence_no, halt_on_error, enabled)
VALUES(6, 'ASR_INVENTORY_NOT_FOUND', 'CHECK_INVENTORY_LINKAGE', 'SQL', 'QUERY_TEMPLATE_EXECUTOR', 'ZP_INVENTORY_BY_REQUEST_LINKAGE', '{"accountId":"string"}', '{"rows":"array"}', '{"queryCode":"ZP_INVENTORY_BY_REQUEST_LINKAGE"}', 30, false, true);
INSERT INTO ce_mcp_playbook_step
(step_id, playbook_code, step_code, step_type, executor_code, template_code, input_contract, output_contract, config_json, sequence_no, halt_on_error, enabled)
VALUES(7, 'ASR_INVENTORY_NOT_FOUND', 'SUMMARIZE_INVENTORY', 'SUMMARY', 'SUMMARY_RENDERER', NULL, '{"requestRows":"array","uiRows":"array","inventoryRows":"array"}', '{"summary":"string"}', '{"summaryStyle":"inventory_gap"}', 40, true, true);
INSERT INTO ce_mcp_playbook_step
(step_id, playbook_code, step_code, step_type, executor_code, template_code, input_contract, output_contract, config_json, sequence_no, halt_on_error, enabled)
VALUES(8, 'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'RESOLVE_TIME_WINDOW', 'DERIVED_FIELD', 'TIME_WINDOW_DERIVER', NULL, '{"hours":"integer"}', '{"fromTs":"timestamp"}', '{"hours":24,"isStart":true}', 10, true, true);
INSERT INTO ce_mcp_playbook_step
(step_id, playbook_code, step_code, step_type, executor_code, template_code, input_contract, output_contract, config_json, sequence_no, halt_on_error, enabled)
VALUES(9, 'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'CHECK_DISCONNECT_CHAIN', 'SQL', 'QUERY_TEMPLATE_EXECUTOR', 'ZP_DISCONNECT_CHAIN_BY_REQUEST', '{"zp_request_id":"string"}', '{"rows":"array"}', '{"queryCode":"ZP_DISCONNECT_CHAIN_BY_REQUEST"}', 20, false, true);
INSERT INTO ce_mcp_playbook_step
(step_id, playbook_code, step_code, step_type, executor_code, template_code, input_contract, output_contract, config_json, sequence_no, halt_on_error, enabled)
VALUES(10, 'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'SUMMARIZE_SUBMIT_FAILURE', 'SUMMARY', 'SUMMARY_RENDERER', NULL, '{"rows":"array"}', '{"summary":"string"}', '{"summaryStyle":"submit_failure"}', 30, true, true);
INSERT INTO ce_mcp_playbook_step
(step_id, playbook_code, step_code, step_type, executor_code, template_code, input_contract, output_contract, config_json, sequence_no, halt_on_error, enabled)
VALUES(11, 'ZPOS_BILLBANK_GAP', 'CHECK_PROPAGATION_GAP', 'SQL', 'QUERY_TEMPLATE_EXECUTOR', 'ZP_BILLBANK_RECON_GAPS', '{"zpDisconnectId":"string"}', '{"rows":"array"}', '{"queryCode":"ZP_BILLBANK_RECON_GAPS","isStart":true}', 10, false, true);
INSERT INTO ce_mcp_playbook_step
(step_id, playbook_code, step_code, step_type, executor_code, template_code, input_contract, output_contract, config_json, sequence_no, halt_on_error, enabled)
VALUES(12, 'ZPOS_BILLBANK_GAP', 'SUMMARIZE_BILLBANK_GAP', 'SUMMARY', 'SUMMARY_RENDERER', NULL, '{"rows":"array"}', '{"summary":"string"}', '{"summaryStyle":"reconciliation_gap"}', 20, true, true);




INSERT INTO ce_mcp_playbook_transition
(transition_id, playbook_code, from_step_code, outcome_code, to_step_code, condition_expr, priority, enabled)
VALUES(1, 'ASR_ASSIGN_REJECT_AUDIT', 'RESOLVE_TIME_WINDOW', 'SUCCESS', 'LOOKUP_ASSIGN_REJECT', 'true', 10, true);
INSERT INTO ce_mcp_playbook_transition
(transition_id, playbook_code, from_step_code, outcome_code, to_step_code, condition_expr, priority, enabled)
VALUES(2, 'ASR_ASSIGN_REJECT_AUDIT', 'LOOKUP_ASSIGN_REJECT', 'SUCCESS', 'SUMMARIZE_AUDIT', 'true', 20, true);
INSERT INTO ce_mcp_playbook_transition
(transition_id, playbook_code, from_step_code, outcome_code, to_step_code, condition_expr, priority, enabled)
VALUES(3, 'ASR_INVENTORY_NOT_FOUND', 'LOOKUP_REQUEST', 'SUCCESS', 'LOOKUP_CURRENT_UI', 'true', 10, true);
INSERT INTO ce_mcp_playbook_transition
(transition_id, playbook_code, from_step_code, outcome_code, to_step_code, condition_expr, priority, enabled)
VALUES(4, 'ASR_INVENTORY_NOT_FOUND', 'LOOKUP_CURRENT_UI', 'SUCCESS', 'CHECK_INVENTORY_LINKAGE', 'true', 20, true);
INSERT INTO ce_mcp_playbook_transition
(transition_id, playbook_code, from_step_code, outcome_code, to_step_code, condition_expr, priority, enabled)
VALUES(5, 'ASR_INVENTORY_NOT_FOUND', 'CHECK_INVENTORY_LINKAGE', 'SUCCESS', 'SUMMARIZE_INVENTORY', 'true', 30, true);
INSERT INTO ce_mcp_playbook_transition
(transition_id, playbook_code, from_step_code, outcome_code, to_step_code, condition_expr, priority, enabled)
VALUES(6, 'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'RESOLVE_TIME_WINDOW', 'SUCCESS', 'CHECK_DISCONNECT_CHAIN', 'true', 10, true);
INSERT INTO ce_mcp_playbook_transition
(transition_id, playbook_code, from_step_code, outcome_code, to_step_code, condition_expr, priority, enabled)
VALUES(7, 'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'CHECK_DISCONNECT_CHAIN', 'SUCCESS', 'SUMMARIZE_SUBMIT_FAILURE', 'true', 20, true);
INSERT INTO ce_mcp_playbook_transition
(transition_id, playbook_code, from_step_code, outcome_code, to_step_code, condition_expr, priority, enabled)
VALUES(8, 'ZPOS_BILLBANK_GAP', 'CHECK_PROPAGATION_GAP', 'SUCCESS', 'SUMMARIZE_BILLBANK_GAP', 'true', 10, true);


INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(1, 'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'limit', 'DEFAULT', 'DEFAULT_LIMIT', '50', true, 'TO_INTEGER', 'Default request lookup limit.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(2, 'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'zp_request_id', 'CASE_CONTEXT', 'knownIds.zp_request_id', NULL, false, NULL, 'Optional request id from resolved case context.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(3, 'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'zp_connection_id', 'CASE_CONTEXT', 'knownIds.zp_connection_id', NULL, false, NULL, 'Optional connection id from resolved case context.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(4, 'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'account_id', 'CASE_CONTEXT', 'knownIds.accountId', NULL, false, NULL, 'Optional account id from resolved case context or direct args.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(5, 'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'acc_loc_id', 'CASE_CONTEXT', 'knownIds.accLocId', NULL, false, NULL, 'Optional account-location id from resolved case context or direct args.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(6, 'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'zp_customer_name', 'CASE_CONTEXT', 'customerName', NULL, false, 'TRIM', 'Optional customer name from resolved case context.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(7, 'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'zp_cust_zip', 'CASE_CONTEXT', 'customerZip', NULL, false, NULL, 'Optional zip code from resolved case context.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(8, 'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'zp_contact_number', 'CASE_CONTEXT', 'contactNumber', NULL, false, NULL, 'Optional contact number from resolved case context.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(9, 'ZP_UI_CURRENT_BY_REQUEST', 'zp_request_id', 'PREV_STEP_OUTPUT', 'ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME[0].zp_request_id', NULL, true, NULL, 'Use the first resolved request id.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(10, 'ZP_UI_CURRENT_BY_REQUEST', 'limit', 'DEFAULT', 'DEFAULT_LIMIT', '1', true, 'TO_INTEGER', 'Only one current UI row is expected.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(11, 'ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS', 'from_action_id', 'STATUS_DICTIONARY', 'ZP_UI_ACTION.zp_action_id.ASSIGNED', '200', true, NULL, 'Resolve ASSIGNED action code.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(12, 'ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS', 'to_action_id', 'STATUS_DICTIONARY', 'ZP_UI_ACTION.zp_action_id.REJECTED', '400', true, NULL, 'Resolve REJECTED action code.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(13, 'ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS', 'from_ts', 'DERIVED_CONTEXT', 'timeWindow.fromTs', NULL, true, NULL, 'Lower bound derived from the rolling time window.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(14, 'ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS', 'limit', 'DEFAULT', 'DEFAULT_LIMIT', '100', true, 'TO_INTEGER', 'Default transition audit limit.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(15, 'ZP_INVENTORY_BY_REQUEST_LINKAGE', 'account_id', 'PREV_STEP_OUTPUT', 'LOOKUP_REQUEST[0].account_id', NULL, false, NULL, 'Account id from the resolved request.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(16, 'ZP_INVENTORY_BY_REQUEST_LINKAGE', 'acc_loc_id', 'PREV_STEP_OUTPUT', 'LOOKUP_REQUEST[0].acc_loc_id', NULL, false, NULL, 'Account-location id from the resolved request.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(17, 'ZP_INVENTORY_BY_REQUEST_LINKAGE', 'customer_id', 'PREV_STEP_OUTPUT', 'LOOKUP_REQUEST[0].customer_id', NULL, false, NULL, 'Customer id from the resolved request.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(18, 'ZP_INVENTORY_BY_REQUEST_LINKAGE', 'zp_connection_id', 'PREV_STEP_OUTPUT', 'LOOKUP_REQUEST[0].zp_connection_id', NULL, false, NULL, 'Connection id from the resolved request.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(19, 'ZP_INVENTORY_BY_REQUEST_LINKAGE', 'limit', 'DEFAULT', 'DEFAULT_LIMIT', '1', true, 'TO_INTEGER', 'Inventory lookup limit.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(20, 'ZP_DISCONNECT_CHAIN_BY_REQUEST', 'zp_request_id', 'CASE_CONTEXT', 'knownIds.zp_request_id', NULL, false, NULL, 'Optional request identifier if available.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(21, 'ZP_DISCONNECT_CHAIN_BY_REQUEST', 'zp_connection_id', 'CASE_CONTEXT', 'knownIds.zp_connection_id', NULL, false, NULL, 'Optional connection identifier if available.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(22, 'ZP_DISCONNECT_CHAIN_BY_REQUEST', 'from_ts', 'DERIVED_CONTEXT', 'timeWindow.fromTs', NULL, true, NULL, 'Threshold timestamp derived from the rolling time window.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(23, 'ZP_DISCONNECT_CHAIN_BY_REQUEST', 'limit', 'DEFAULT', 'DEFAULT_LIMIT', '25', true, 'TO_INTEGER', 'Disconnect chain trace limit.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(24, 'ZP_BILLBANK_RECON_GAPS', 'zpDisconnectId', 'CASE_CONTEXT', 'knownIds.zpDisconnectId', NULL, false, NULL, 'Optional disconnect identifier when explicitly provided.', true);
INSERT INTO ce_mcp_query_param_rule
(param_rule_id, query_code, param_name, source_type, source_key, default_value, required_flag, transform_rule, description, enabled)
VALUES(25, 'ZP_BILLBANK_RECON_GAPS', 'limit', 'DEFAULT', 'DEFAULT_LIMIT', '50', true, 'TO_INTEGER', 'Reconciliation gap limit.', true);



INSERT INTO ce_mcp_query_template
(query_code, playbook_code, executor_code, purpose, sql_template, required_params, optional_params, result_contract, safety_class, default_limit, enabled, description, created_at)
VALUES('ZP_UI_CURRENT_BY_REQUEST', 'ASR_INVENTORY_NOT_FOUND', 'QUERY_TEMPLATE_EXECUTOR', 'Find the current UI row for a request and expose the latest ASR owner, notes, action, and queue.', 'SELECT u.zp_request_id, u.zp_asr_team_member_id, u.zp_asr_team_notes, u.zp_action_id, u.zp_queue_code, u.last_updated_at
FROM zp_ui_data u
WHERE u.zp_request_id = :zp_request_id
LIMIT :limit', '["zp_request_id","limit"]', '[]', '{"primaryKeys":["zp_request_id"],"fields":["zp_request_id","zp_asr_team_member_id","zp_asr_team_notes","zp_action_id","zp_queue_code","last_updated_at"]}', 'READ_ONLY_STRICT', 1, true, 'Current UI lookup using the live Zapper schema.', '2026-03-01 21:27:09.640');
INSERT INTO ce_mcp_query_template
(query_code, playbook_code, executor_code, purpose, sql_template, required_params, optional_params, result_contract, safety_class, default_limit, enabled, description, created_at)
VALUES('ZP_UI_HISTORY_ASSIGN_TO_REJECT_LAST_HOURS', 'ASR_ASSIGN_REJECT_AUDIT', 'QUERY_TEMPLATE_EXECUTOR', 'Find requests whose UI history moved from ASSIGNED to REJECTED in a rolling time window.', 'SELECT h2.zp_request_id,
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
LIMIT :limit', '["from_action_id","to_action_id","from_ts","limit"]', '[]', '{"primaryKeys":["zp_request_id"],"fields":["zp_request_id","assigned_at","rejected_at","assigned_by_member_id","rejected_by_member_id"]}', 'READ_ONLY_STRICT', 100, true, 'Transition audit using the concrete Zapper UI history table.', '2026-03-01 21:27:09.640');
INSERT INTO ce_mcp_query_template
(query_code, playbook_code, executor_code, purpose, sql_template, required_params, optional_params, result_contract, safety_class, default_limit, enabled, description, created_at)
VALUES('ZP_INVENTORY_BY_REQUEST_LINKAGE', 'ASR_INVENTORY_NOT_FOUND', 'QUERY_TEMPLATE_EXECUTOR', 'Check whether an inventory row exists for the request''s account-location and connection linkage.', 'SELECT i.inventory_id, i.account_id, i.customer_id, i.acc_loc_id, i.zp_connection_id,
       i.zapper_id, i.inventory_status, i.provisioned_flag, i.inventory_sync_status, i.last_verified_at
FROM zp_inventory_service i
WHERE (:account_id IS NULL OR i.account_id = :account_id)
  AND (:acc_loc_id IS NULL OR i.acc_loc_id = :acc_loc_id)
  AND (:customer_id IS NULL OR i.customer_id = :customer_id)
  AND (:zp_connection_id IS NULL OR i.zp_connection_id = :zp_connection_id)
ORDER BY i.updated_at DESC
LIMIT :limit', '["limit"]', '["account_id","acc_loc_id","customer_id","zp_connection_id"]', '{"fields":["inventory_id","account_id","customer_id","acc_loc_id","zp_connection_id","zapper_id","inventory_status","provisioned_flag","inventory_sync_status","last_verified_at"]}', 'READ_ONLY_STRICT', 1, true, 'Real inventory lookup against zp_inventory_service.', '2026-03-01 21:27:09.640');
INSERT INTO ce_mcp_query_template
(query_code, playbook_code, executor_code, purpose, sql_template, required_params, optional_params, result_contract, safety_class, default_limit, enabled, description, created_at)
VALUES('ZP_DISCONNECT_CHAIN_BY_REQUEST', 'ASO_SUBMITTED_NO_ZPDISCONNECTID_24H', 'QUERY_TEMPLATE_EXECUTOR', 'Trace submitted disconnects older than the requested threshold and show missing disconnect id or failed downstream checks.', 'SELECT o.zp_request_id, o.zp_connection_id, o.submitted_at AS submit_ts, o.don,
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
LIMIT :limit', '["from_ts","limit"]', '["zp_request_id","zp_connection_id"]', '{"fields":["zp_request_id","zp_connection_id","submit_ts","don","downstream_status","zp_disconnect_id","failing_check_code","failing_reason"]}', 'READ_ONLY_STRICT', 1, true, 'Real disconnect-chain trace against zp_disconnect_order and zp_order_downstream_check.', '2026-03-01 21:27:09.640');
INSERT INTO ce_mcp_query_template
(query_code, playbook_code, executor_code, purpose, sql_template, required_params, optional_params, result_contract, safety_class, default_limit, enabled, description, created_at)
VALUES('ZP_BILLBANK_RECON_GAPS', 'ZPOS_BILLBANK_GAP', 'QUERY_TEMPLATE_EXECUTOR', 'Find order-side disconnect ids that should exist in BillBank but are missing there.', 'SELECT o.zp_request_id, o.don, o.zp_disconnect_id, o.zapper_id, o.submitted_at,
       ''MISSING_IN_BILLBANK'' AS billbank_status
FROM zp_disconnect_order o
LEFT JOIN zp_billbank_record b
  ON o.zp_disconnect_id = b.zp_disconnect_id
WHERE o.zp_disconnect_id IS NOT NULL
  AND b.billbank_id IS NULL
  AND (:zpDisconnectId IS NULL OR o.zp_disconnect_id = :zpDisconnectId)
ORDER BY o.submitted_at DESC
LIMIT :limit', '["limit"]', '["limit"]', '{"fields":["zp_request_id","don","zp_disconnect_id","zapper_id","submitted_at","billbank_status"]}', 'READ_ONLY_STRICT', 50, true, 'Real reconciliation query against zp_disconnect_order and zp_billbank_record.', '2026-03-01 21:27:09.640');
INSERT INTO ce_mcp_query_template
(query_code, playbook_code, executor_code, purpose, sql_template, required_params, optional_params, result_contract, safety_class, default_limit, enabled, description, created_at)
VALUES('ZP_REQUEST_LOOKUP_BY_ACCOUNT_OR_NAME', 'ASR_INVENTORY_NOT_FOUND', 'QUERY_TEMPLATE_EXECUTOR', 'Find requests by request id, connection id, account, account-location, customer name, zip, or contact number.', 'SELECT r.zp_request_id, r.zp_connection_id, r.account_id, r.customer_id, r.location_id, r.acc_loc_id,
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
LIMIT :limit', '["limit"]', '["zp_request_id","zp_connection_id","account_id","acc_loc_id","zp_customer_name","zp_cust_zip","zp_contact_number"]', '{"primaryKeys":["zp_request_id"],"fields":["zp_request_id","zp_connection_id","account_id","customer_id","location_id","acc_loc_id","zp_customer_name","zp_cust_zip","zp_contact_number","request_status","requested_provider","submitted_to_aso_at"]}', 'READ_ONLY_STRICT', 50, true, 'Request lookup across the concrete Zapper transaction schema.', '2026-03-01 21:27:09.640');


INSERT INTO ce_mcp_status_dictionary
(status_id, dictionary_name, field_name, code_value, code_label, business_meaning, synonyms, enabled)
VALUES(1, 'ZP_UI_ACTION', 'zp_action_id', '100', 'REVIEW', 'ASR is reviewing the request.', 'review,under review', true);
INSERT INTO ce_mcp_status_dictionary
(status_id, dictionary_name, field_name, code_value, code_label, business_meaning, synonyms, enabled)
VALUES(2, 'ZP_UI_ACTION', 'zp_action_id', '200', 'ASSIGNED', 'ASR has assigned or taken ownership of the request.', 'assign,assigned', true);
INSERT INTO ce_mcp_status_dictionary
(status_id, dictionary_name, field_name, code_value, code_label, business_meaning, synonyms, enabled)
VALUES(3, 'ZP_UI_ACTION', 'zp_action_id', '300', 'SIGNOFF', 'ASR validation is complete and ready for handoff.', 'signoff,approved', true);
INSERT INTO ce_mcp_status_dictionary
(status_id, dictionary_name, field_name, code_value, code_label, business_meaning, synonyms, enabled)
VALUES(4, 'ZP_UI_ACTION', 'zp_action_id', '400', 'REJECTED', 'ASR rejected the request during validation.', 'reject,rejected', true);
INSERT INTO ce_mcp_status_dictionary
(status_id, dictionary_name, field_name, code_value, code_label, business_meaning, synonyms, enabled)
VALUES(5, 'ZP_UI_ACTION', 'zp_action_id', '500', 'SUBMITTED', 'ASO submitted the request downstream.', 'submit,submitted', true);


INSERT INTO ce_mcp_system_node
(system_code, system_name, system_type, description, metadata_json, llm_hint, enabled, created_at)
VALUES('ZAPPER_UI', 'Zapper UI', 'UI', 'React UI used by operators.', '{"layer":"frontend"}', 'Entry point where operator actions originate.', true, '2026-03-01 21:27:09.598');
INSERT INTO ce_mcp_system_node
(system_code, system_name, system_type, description, metadata_json, llm_hint, enabled, created_at)
VALUES('ZAPPER_CENTRAL', 'ZapperCentral', 'ORCHESTRATOR', 'Central orchestration layer that receives requests and runs nightly processing.', '{"layer":"orchestrator"}', 'Main coordinator that fans out to downstream systems.', true, '2026-03-01 21:27:09.598');
INSERT INTO ce_mcp_system_node
(system_code, system_name, system_type, description, metadata_json, llm_hint, enabled, created_at)
VALUES('ZAPPER_INV', 'Zapper Inventory', 'DB_APP', 'Inventory system used to validate service identifiers and connection lineage.', '{"layer":"inventory"}', 'Use when checking whether the service/inventory record exists.', true, '2026-03-01 21:27:09.598');
INSERT INTO ce_mcp_system_node
(system_code, system_name, system_type, description, metadata_json, llm_hint, enabled, created_at)
VALUES('ZAPPER_LS', 'Zapper Location Service', 'SERVICE', 'Service used to validate location and zip details.', '{"layer":"validation"}', 'Use when the issue involves location or zip validation.', true, '2026-03-01 21:27:09.598');
INSERT INTO ce_mcp_system_node
(system_code, system_name, system_type, description, metadata_json, llm_hint, enabled, created_at)
VALUES('ZAPPER_ORDER_SERVICE', 'Zapper Order Service', 'SERVICE', 'Service that creates disconnect orders and runs downstream order checks.', '{"layer":"order"}', 'Use when a submit should generate order identifiers or disconnect outputs.', true, '2026-03-01 21:27:09.598');
INSERT INTO ce_mcp_system_node
(system_code, system_name, system_type, description, metadata_json, llm_hint, enabled, created_at)
VALUES('ZAPPER_BILLBANK', 'Zapper BillBank', 'DB_APP', 'Billing system that should receive billing and disconnect identifiers.', '{"layer":"billing"}', 'Use when reconciling disconnect visibility in billing.', true, '2026-03-01 21:27:09.598');


INSERT INTO ce_mcp_system_relation
(system_relation_id, from_system_code, relation_type, to_system_code, sequence_no, description, enabled)
VALUES(1, 'ZAPPER_UI', 'CALLS', 'ZAPPER_CENTRAL', 1, 'UI sends operator requests into the central orchestration application.', true);
INSERT INTO ce_mcp_system_relation
(system_relation_id, from_system_code, relation_type, to_system_code, sequence_no, description, enabled)
VALUES(2, 'ZAPPER_CENTRAL', 'VALIDATES_WITH', 'ZAPPER_INV', 2, 'Nightly processing validates that inventory records exist.', true);
INSERT INTO ce_mcp_system_relation
(system_relation_id, from_system_code, relation_type, to_system_code, sequence_no, description, enabled)
VALUES(3, 'ZAPPER_CENTRAL', 'VALIDATES_WITH', 'ZAPPER_LS', 3, 'Location details are checked before workflow progression.', true);
INSERT INTO ce_mcp_system_relation
(system_relation_id, from_system_code, relation_type, to_system_code, sequence_no, description, enabled)
VALUES(4, 'ZAPPER_CENTRAL', 'SUBMITS_TO', 'ZAPPER_ORDER_SERVICE', 4, 'ASO submit sends the disconnect into the order domain.', true);
INSERT INTO ce_mcp_system_relation
(system_relation_id, from_system_code, relation_type, to_system_code, sequence_no, description, enabled)
VALUES(5, 'ZAPPER_ORDER_SERVICE', 'PROPAGATES_TO', 'ZAPPER_BILLBANK', 5, 'Generated disconnect IDs should become visible in billing.', true);


INSERT INTO ce_mcp_tool
(tool_id, tool_code, tool_group, intent_code, state_code, enabled, description, created_at)
VALUES(10201, 'loan.credit.rating.check', 'HTTP_API', 'LOAN_APPLICATION', 'PROCESS_APPLICATION', true, 'Step 1: credit rating check', '2026-02-27 22:28:19.835');
INSERT INTO ce_mcp_tool
(tool_id, tool_code, tool_group, intent_code, state_code, enabled, description, created_at)
VALUES(10202, 'loan.credit.fraud.check', 'HTTP_API', 'LOAN_APPLICATION', 'PROCESS_APPLICATION', true, 'Step 2: fraud check', '2026-02-27 22:28:19.835');
INSERT INTO ce_mcp_tool
(tool_id, tool_code, tool_group, intent_code, state_code, enabled, description, created_at)
VALUES(10203, 'loan.debt.credit.summary', 'HTTP_API', 'LOAN_APPLICATION', 'PROCESS_APPLICATION', true, 'Step 3: debt and credit summary', '2026-02-27 22:28:19.835');
INSERT INTO ce_mcp_tool
(tool_id, tool_code, tool_group, intent_code, state_code, enabled, description, created_at)
VALUES(10204, 'loan.application.submit', 'HTTP_API', 'LOAN_APPLICATION', 'PROCESS_APPLICATION', true, 'Step 4: submit application', '2026-02-27 22:28:19.835');
INSERT INTO ce_mcp_tool
(tool_id, tool_code, tool_group, intent_code, state_code, enabled, description, created_at)
VALUES(9101, 'dbkg.case.resolve', 'DB', 'DBKG_DIAGNOSTICS', 'ANY', true, 'Resolve business case from user language using DB metadata.', '2026-03-01 21:27:09.559');
INSERT INTO ce_mcp_tool
(tool_id, tool_code, tool_group, intent_code, state_code, enabled, description, created_at)
VALUES(9102, 'dbkg.knowledge.lookup', 'DB', 'DBKG_DIAGNOSTICS', 'ANY', true, 'Lookup graph metadata: entities, systems, tables, joins, statuses, and lineage.', '2026-03-01 21:27:09.559');
INSERT INTO ce_mcp_tool
(tool_id, tool_code, tool_group, intent_code, state_code, enabled, description, created_at)
VALUES(9103, 'dbkg.investigate.plan', 'DB', 'DBKG_DIAGNOSTICS', 'ANY', true, 'Choose the highest-confidence playbook and build a step plan from DB metadata.', '2026-03-01 21:27:09.559');
INSERT INTO ce_mcp_tool
(tool_id, tool_code, tool_group, intent_code, state_code, enabled, description, created_at)
VALUES(9104, 'dbkg.investigate.execute', 'DB', 'DBKG_DIAGNOSTICS', 'ANY', true, 'Execute the selected investigation playbook using DB-configured steps and templates.', '2026-03-01 21:27:09.559');
INSERT INTO ce_mcp_tool
(tool_id, tool_code, tool_group, intent_code, state_code, enabled, description, created_at)
VALUES(9105, 'dbkg.playbook.validate', 'DB', 'DBKG_DIAGNOSTICS', 'ANY', true, 'Validate a DBKG playbook graph without executing its investigation steps.', '2026-03-01 21:27:09.559');



INSERT INTO ce_output_schema
(schema_id, intent_code, state_code, json_schema, description, enabled, priority)
VALUES(10, 'DBKG_DIAGNOSTICS', 'ANALYZE', '{"type": "object", "properties": {"hours": {"type": "integer", "description": "Hours only if explicitly stated in the latest effective user input."}, "accLocId": {"type": "string", "description": "Account location identifier explicitly stated in the latest effective user input."}, "accountId": {"type": "string", "description": "Account identifier explicitly stated in the latest effective user input."}, "customerZip": {"type": "string", "description": "Customer zip only if explicitly stated in the latest effective user input."}, "customerName": {"type": "string", "description": "Customer name only if explicitly stated in the latest effective user input."}, "playbookCode": {"type": "string", "description": "Optional playbook code if explicitly provided by the user."}, "contactNumber": {"type": "string", "description": "Contact number only if explicitly stated in the latest effective user input."}, "zp_request_id": {"type": "string", "description": "Disconnect request identifier explicitly stated in the latest effective user input."}, "zpDisconnectId": {"type": "string", "description": "Disconnect identifier explicitly stated in the latest effective user input."}, "zp_connection_id": {"type": "string", "description": "Connection identifier explicitly stated in the latest effective user input."}}, "additionalProperties": false}'::jsonb, 'Structured investigation fields used by the DBKG diagnostic flow. Extract only explicit values from the latest effective user input.', true, 100);


INSERT INTO ce_prompt_template
(template_id, intent_code, state_code, response_type, system_prompt, user_prompt, temperature, enabled, created_at, interaction_mode, interaction_contract)
VALUES(26, 'DBKG_DIAGNOSTICS', 'ANALYZE', 'EXACT', 'You are a production support assistant for Zapper.', 'If the user has not provided enough identifying detail, ask only for the specific missing identifiers needed to investigate. Do not assume identifiers.', 0.00, true, '2026-03-01 21:41:54.328', 'COLLECT', '{"allows":["ask_follow_up","clarify"],"expects":["structured_input"]}');
INSERT INTO ce_prompt_template
(template_id, intent_code, state_code, response_type, system_prompt, user_prompt, temperature, enabled, created_at, interaction_mode, interaction_contract)
VALUES(27, 'DBKG_DIAGNOSTICS', 'COMPLETED', 'DERIVED', 'You are a production support assistant for Zapper.', 'Context JSON:
{{context}}

Use context.mcp.finalAnswer as primary final answer.
Use context.mcp.observations only to validate details.', 0.00, true, '2026-03-01 21:41:54.334', 'FINAL', '{"allows":["summarize"],"expects":["mcp_final_answer"]}');
INSERT INTO ce_prompt_template
(template_id, intent_code, state_code, response_type, system_prompt, user_prompt, temperature, enabled, created_at, interaction_mode, interaction_contract)
VALUES(28, 'DBKG_DIAGNOSTICS', 'ANALYZE', 'SCHEMA_JSON', 'You extract structured investigation fields for Zapper support. Return JSON only. Use only the latest effective user input provided below. Do not infer, guess, normalize, backfill, or fabricate identifiers. If a field value is not explicitly present in the latest effective user input, omit that field entirely. Never generate placeholder values. Never reuse values from prior turns, memory, session state, or hidden context.', 'Latest effective user input:
{{effective_user_input}}

Extract supported schema fields only from the latest effective user input above.

Allowed explicit mappings:
- "AccountId is <value>" or "accountId is <value>" -> accountId
- "accLocId is <value>" -> accLocId
- "zp_request_id is <value>" -> zp_request_id
- "zp_connection_id is <value>" -> zp_connection_id
- "zpDisconnectId is <value>" -> zpDisconnectId

Rules:
- Copy values verbatim only when explicitly present in the latest effective user input.
- If accountId is not explicitly present, do not return accountId.
- If accLocId is not explicitly present, do not return accLocId.
- If no supported fields are explicitly present, return {}.
- Return JSON only.', 0.00, true, '2026-03-01 21:41:54.328', 'COLLECT', '{"allows":["extract"],"expects":["structured_json"]}');


INSERT INTO ce_response
(response_id, intent_code, state_code, output_format, response_type, exact_text, derivation_hint, json_schema, priority, enabled, description, created_at)
VALUES(21, 'GREETING', 'ANY', 'TEXT', 'EXACT', 'Hi 😁, How can I help you today?', NULL, NULL, 50, true, 'Global greetings response', '2026-02-26 01:57:06.992');
INSERT INTO ce_response
(response_id, intent_code, state_code, output_format, response_type, exact_text, derivation_hint, json_schema, priority, enabled, description, created_at)
VALUES(22, 'UNKNOWN', 'ANY', 'TEXT', 'EXACT', 'Sorry, I did not understand that. Please rephrase.', NULL, NULL, 999, true, 'Global fallback response', '2026-02-26 01:57:06.992');
INSERT INTO ce_response
(response_id, intent_code, state_code, output_format, response_type, exact_text, derivation_hint, json_schema, priority, enabled, description, created_at)
VALUES(26, 'DBKG_DIAGNOSTICS', 'COMPLETED', 'TEXT', 'DERIVED', NULL, 'Render final answer from context.mcp.finalAnswer.', NULL, 10, true, 'Final DBKG response.', '2026-03-01 21:41:54.339');
INSERT INTO ce_response
(response_id, intent_code, state_code, output_format, response_type, exact_text, derivation_hint, json_schema, priority, enabled, description, created_at)
VALUES(25, 'DBKG_DIAGNOSTICS', 'ANALYZE', 'TEXT', 'EXACT', 'To investigate the disconnect issue, share any identifiers you have: zp_request_id, zp_connection_id, accountId, accLocId, or zpDisconnectId. If you do not have identifiers, describe the exact error message and where the disconnect flow is failing.', NULL, NULL, 10, true, 'Fallback follow-up response during ANALYZE.', '2026-03-01 21:41:54.336');



INSERT INTO ce_rule
(rule_id, phase, intent_code, state_code, rule_type, match_pattern, "action", action_value, priority, enabled, description, created_at)
VALUES(33, 'POST_AGENT_INTENT', 'DBKG_DIAGNOSTICS', 'IDLE', 'EXACT', 'ANY', 'SET_STATE', 'ANALYZE', 10, true, 'Enter ANALYZE state.', '2026-03-01 21:41:54.341');
INSERT INTO ce_rule
(rule_id, phase, intent_code, state_code, rule_type, match_pattern, "action", action_value, priority, enabled, description, created_at)
VALUES(34, 'POST_AGENT_MCP', 'DBKG_DIAGNOSTICS', 'ANALYZE', 'JSON_PATH', '$[?(@.context.mcp.lifecycle.finished==true)]', 'SET_STATE', 'COMPLETED', 10, true, 'Close DBKG flow after MCP.', '2026-03-01 21:41:54.345');



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