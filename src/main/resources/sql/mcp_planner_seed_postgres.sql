-- ConvEngine 2.0.7+ canonical MCP seed pack (Postgres)
-- Includes Example 1 (ORDER_DIAGNOSTICS) and Example 2 (LOAN_APPLICATION)
-- Covers ce_intent, ce_intent_classifier, ce_output_schema, ce_prompt_template,
-- ce_response, ce_rule, ce_mcp_tool, ce_mcp_planner.

SET search_path TO v2, public;

-- -----------------------------------------------------------------------------
-- Cleanup (idempotent)
-- -----------------------------------------------------------------------------
DELETE FROM ce_rule WHERE intent_code IN ('ORDER_DIAGNOSTICS', 'LOAN_APPLICATION');
DELETE FROM ce_response WHERE intent_code IN ('ORDER_DIAGNOSTICS', 'LOAN_APPLICATION');
DELETE FROM ce_prompt_template WHERE intent_code IN ('ORDER_DIAGNOSTICS', 'LOAN_APPLICATION');
DELETE FROM ce_output_schema WHERE intent_code IN ('ORDER_DIAGNOSTICS', 'LOAN_APPLICATION');
DELETE FROM ce_intent_classifier WHERE intent_code IN ('ORDER_DIAGNOSTICS', 'LOAN_APPLICATION');
DELETE FROM ce_mcp_planner WHERE planner_id IN (5101, 5201, 5202);
DELETE FROM ce_mcp_db_tool WHERE tool_id IN (
    SELECT tool_id FROM ce_mcp_tool WHERE tool_code IN (
        'mock.order.status',
        'mock.order.async.trace',
        'loan.credit.rating.check',
        'loan.credit.fraud.check',
        'loan.debt.credit.summary',
        'loan.application.submit'
    )
);
DELETE FROM ce_mcp_tool WHERE tool_code IN (
    'mock.order.status',
    'mock.order.async.trace',
    'loan.credit.rating.check',
    'loan.credit.fraud.check',
    'loan.debt.credit.summary',
    'loan.application.submit'
);
DELETE FROM ce_intent WHERE intent_code IN ('ORDER_DIAGNOSTICS', 'LOAN_APPLICATION');

-- -----------------------------------------------------------------------------
-- Intents
-- -----------------------------------------------------------------------------
INSERT INTO ce_intent (intent_code, description, priority, enabled, display_name, llm_hint)
VALUES
('ORDER_DIAGNOSTICS', 'Diagnose submitted order and missing async callback using MCP tools', 40, true, 'Order Diagnostics',
 'Use MCP HTTP tools to inspect order status and async callback trace, then summarize from MCP final answer.'),
('LOAN_APPLICATION', 'Loan application eligibility and submission workflow', 30, true, 'Loan Application',
 'Handle loan checks and submission using MCP APIs in strict sequence.');

-- -----------------------------------------------------------------------------
-- Intent classifiers (state_code explicit, no null)
-- -----------------------------------------------------------------------------
INSERT INTO ce_intent_classifier (intent_code, state_code, rule_type, pattern, priority, enabled, description)
VALUES
('ORDER_DIAGNOSTICS', 'UNKNOWN', 'REGEX', '(?i)\b(order|submitted|async|callback|trace|status|diagnostics?)\b', 35, true,
 'Order diagnostics classifier'),
('LOAN_APPLICATION', 'UNKNOWN', 'REGEX', '(?i)\b(loan|apply loan|personal loan|home loan|eligibility|credit score|loan application)\b', 30, true,
 'Loan application classifier');

-- -----------------------------------------------------------------------------
-- Output schemas
-- -----------------------------------------------------------------------------
INSERT INTO ce_output_schema (intent_code, state_code, json_schema, description, enabled, priority)
VALUES
(
    'ORDER_DIAGNOSTICS',
    'ANALYZE',
    '{
      "type":"object",
      "properties":{
        "orderId":{"type":"string"},
        "customerId":{"type":"string"}
      }
    }'::jsonb,
    'Optional extraction fields for order diagnostics',
    true,
    1
),
(
    'LOAN_APPLICATION',
    'ELIGIBILITY_GATE',
    '{
      "type":"object",
      "properties":{
        "customerId":{"type":"string"},
        "requestedAmount":{"type":"number"},
        "tenureMonths":{"type":"integer"}
      },
      "required":["customerId","requestedAmount","tenureMonths"]
    }'::jsonb,
    'Loan application required fields for MCP chain',
    true,
    1
);

-- -----------------------------------------------------------------------------
-- Prompt templates
-- -----------------------------------------------------------------------------
INSERT INTO ce_prompt_template (intent_code, state_code, response_type, system_prompt, user_prompt, temperature, enabled)
VALUES
(
    'ORDER_DIAGNOSTICS',
    'ANALYZE',
    'TEXT',
    'You are an order diagnostics summarizer.',
    'Context JSON:\n{{context}}\n\nRead context.mcp.observations and context.mcp.finalAnswer. Summarize order status and callback diagnostics using only observed evidence.',
    0.00,
    true
),
(
    'ORDER_DIAGNOSTICS',
    'COMPLETED',
    'TEXT',
    'You are an order diagnostics summarizer.',
    'Context JSON:\n{{context}}\n\nUse context.mcp.finalAnswer as primary final response. Use context.mcp.observations only for supporting details.',
    0.00,
    true
),
(
    'LOAN_APPLICATION',
    'ELIGIBILITY_GATE',
    'TEXT',
    'You are a strict loan decision summarizer.',
    'Context JSON:\n{{context}}\n\nRead context.mcp.observations and context.mcp.finalAnswer when present. Mention rating, fraud flag, dti, availableCredit, and applicationId when available.',
    0.00,
    true
),
(
    'LOAN_APPLICATION',
    'COMPLETED',
    'TEXT',
    'You are a strict loan decision summarizer.',
    'Context JSON:\n{{context}}\n\nUse context.mcp.finalAnswer as primary final answer. Use context.mcp.observations only to validate details.',
    0.00,
    true
);

-- -----------------------------------------------------------------------------
-- Responses
-- -----------------------------------------------------------------------------
INSERT INTO ce_response (intent_code, state_code, output_format, response_type, exact_text, derivation_hint, json_schema, priority, enabled, description)
VALUES
('ORDER_DIAGNOSTICS', 'ANALYZE', 'TEXT', 'DERIVED',
 NULL,
 'Use context.mcp.observations and context.mcp.finalAnswer to diagnose submitted order and callback status.',
 NULL, 20, true, 'Order diagnostics derived response in ANALYZE state'),
('ORDER_DIAGNOSTICS', 'COMPLETED', 'TEXT', 'DERIVED',
 NULL,
 'Use context.mcp.finalAnswer as final answer. Keep concise and evidence-based from context.mcp.observations.',
 NULL, 30, true, 'Order diagnostics completed response derived from MCP final answer'),
('LOAN_APPLICATION', 'IDLE', 'TEXT', 'EXACT',
 'Please share customerId, requestedAmount, and tenureMonths to evaluate your loan application.',
 NULL, NULL, 10, true, 'Loan intake prompt'),
('LOAN_APPLICATION', 'ELIGIBILITY_GATE', 'TEXT', 'DERIVED',
 NULL,
 'Use context.mcp.observations and context.mcp.finalAnswer to explain decision. Reject on low rating/fraud/poor profile. Include applicationId when submitted.',
 NULL, 20, true, 'Loan decision derived from MCP chain'),
('LOAN_APPLICATION', 'COMPLETED', 'TEXT', 'DERIVED',
 NULL,
 'Use context.mcp.finalAnswer as primary answer. Validate wording with context.mcp.observations and include evidence fields when present.',
 NULL, 30, true, 'Loan completed response derived from MCP final answer');

-- -----------------------------------------------------------------------------
-- Rules (new phase names)
-- -----------------------------------------------------------------------------
INSERT INTO ce_rule (phase, intent_code, state_code, rule_type, match_pattern, "action", action_value, priority, enabled, description)
VALUES
('POST_AGENT_INTENT', 'ORDER_DIAGNOSTICS', 'UNKNOWN', 'REGEX', '.*', 'SET_STATE', 'ANALYZE', 40, true,
 'Bootstrap ORDER_DIAGNOSTICS into ANALYZE when classifier sets UNKNOWN'),
('POST_AGENT_INTENT', 'ORDER_DIAGNOSTICS', 'IDLE', 'REGEX', '.*', 'SET_STATE', 'ANALYZE', 41, true,
 'Bootstrap ORDER_DIAGNOSTICS into ANALYZE from IDLE'),
('POST_AGENT_MCP', 'ORDER_DIAGNOSTICS', 'ANALYZE', 'JSON_PATH',
 '$[?(@.context.mcp.finalAnswer != ''null'' && @.context.mcp.finalAnswer != null && @.context.mcp.finalAnswer != '''')]',
 'SET_STATE', 'COMPLETED', 42, true,
 'Move ORDER_DIAGNOSTICS to COMPLETED when context.mcp.finalAnswer exists'),
('POST_AGENT_INTENT', 'LOAN_APPLICATION', 'UNKNOWN', 'REGEX', '.*', 'SET_STATE', 'ELIGIBILITY_GATE', 60, true,
 'Move LOAN_APPLICATION into ELIGIBILITY_GATE when classifier state is UNKNOWN'),
('POST_AGENT_INTENT', 'LOAN_APPLICATION', 'IDLE', 'REGEX', '.*', 'SET_STATE', 'ELIGIBILITY_GATE', 61, true,
 'Move LOAN_APPLICATION into ELIGIBILITY_GATE when state is IDLE'),
('POST_AGENT_MCP', 'LOAN_APPLICATION', 'ELIGIBILITY_GATE', 'JSON_PATH',
 '$[?(@.context.mcp.finalAnswer != null && @.context.mcp.finalAnswer != '''')]',
 'SET_STATE', 'COMPLETED', 62, true,
 'Move LOAN_APPLICATION to COMPLETED when context.mcp.finalAnswer exists'),
('PRE_RESPONSE_RESOLUTION', 'ORDER_DIAGNOSTICS', 'ANY', 'REGEX', '(?i)\b(reset|restart|start over)\b', 'SET_STATE', 'IDLE', 80, true,
 'Allow reset for order diagnostics flow'),
('PRE_RESPONSE_RESOLUTION', 'LOAN_APPLICATION', 'ANY', 'REGEX', '(?i)\b(reset|restart|start over)\b', 'SET_STATE', 'IDLE', 81, true,
 'Allow reset for loan flow');

-- -----------------------------------------------------------------------------
-- MCP tools (intent/state scoped, no null)
-- -----------------------------------------------------------------------------
INSERT INTO ce_mcp_tool (tool_id, tool_code, tool_group, intent_code, state_code, enabled, description)
VALUES
(9101, 'mock.order.status', 'HTTP_API', 'ORDER_DIAGNOSTICS', 'ANALYZE', true,
 'Fetch order status for diagnostics'),
(9102, 'mock.order.async.trace', 'HTTP_API', 'ORDER_DIAGNOSTICS', 'ANALYZE', true,
 'Fetch async callback trace for diagnostics'),
(9201, 'loan.credit.rating.check', 'HTTP_API', 'LOAN_APPLICATION', 'ELIGIBILITY_GATE', true,
 'Step 1: check customer credit rating from credit union API'),
(9202, 'loan.credit.fraud.check', 'HTTP_API', 'LOAN_APPLICATION', 'ELIGIBILITY_GATE', true,
 'Step 2: if rating > 750, verify customer against fraud API'),
(9203, 'loan.debt.credit.summary', 'HTTP_API', 'LOAN_APPLICATION', 'ELIGIBILITY_GATE', true,
 'Step 3: if fraud clear, fetch debt/credit summary'),
(9204, 'loan.application.submit', 'HTTP_API', 'LOAN_APPLICATION', 'ELIGIBILITY_GATE', true,
 'Step 4: if profile healthy, submit final loan application');

-- -----------------------------------------------------------------------------
-- ce_mcp_planner prompts (example 1 + example 2 + safe default)
-- -----------------------------------------------------------------------------
INSERT INTO ce_mcp_planner (planner_id, intent_code, state_code, system_prompt, user_prompt, enabled, created_at)
VALUES
(
    5101,
    'ORDER_DIAGNOSTICS',
    'ANALYZE',
    'You are an MCP planning agent for order diagnostics.\nTool order:\n1) mock.order.status\n2) mock.order.async.trace\n3) ANSWER with concise diagnosis from observations.\nReturn JSON only. Do not invent values.',
    'User input:\n{{user_input}}\n\nContext JSON:\n{{context}}\n\nAvailable MCP tools:\n{{mcp_tools}}\n\nExisting MCP observations:\n{{mcp_observations}}\n\nReturn strict JSON:\n{\n  "action":"CALL_TOOL" | "ANSWER",\n  "tool_code":"<tool_code_or_null>",\n  "args":{},\n  "answer":"<text_or_null>"\n}',
    true,
    now()
),
(
    5201,
    'ANY',
    'ANY',
    'You are an MCP planning agent inside ConvEngine. Decide whether to CALL_TOOL or ANSWER. Be conservative, safe, and do not hallucinate missing data. Return JSON only.',
    'User input:\n{{user_input}}\n\nContext JSON:\n{{context}}\n\nAvailable MCP tools:\n{{mcp_tools}}\n\nExisting MCP observations:\n{{mcp_observations}}\n\nReturn strict JSON:\n{\n  "action":"CALL_TOOL" | "ANSWER",\n  "tool_code":"<tool_code_or_null>",\n  "args":{},\n  "answer":"<text_or_null>"\n}',
    true,
    now()
),
(
    5202,
    'LOAN_APPLICATION',
    'ELIGIBILITY_GATE',
    'You are an MCP planning agent for a loan application workflow.\nYou MUST follow tool order:\n1) loan.credit.rating.check\n2) If creditRating <= 750 => ANSWER reject\n3) Else loan.credit.fraud.check\n4) If flagged=true => ANSWER reject\n5) Else loan.debt.credit.summary\n6) If dti > 0.65 or availableCredit < requestedAmount*0.15 => ANSWER reject/manual-review\n7) Else loan.application.submit\n8) ANSWER with applicationId.\nReturn JSON only. Never invent unknown values.',
    'User input:\n{{user_input}}\n\nContext JSON:\n{{context}}\n\nAvailable MCP tools:\n{{mcp_tools}}\n\nExisting MCP observations:\n{{mcp_observations}}\n\nReturn strict JSON:\n{\n  "action":"CALL_TOOL" | "ANSWER",\n  "tool_code":"<tool_code_or_null>",\n  "args":{},\n  "answer":"<text_or_null>"\n}',
    true,
    now()
)
ON CONFLICT (planner_id) DO UPDATE
SET
    intent_code = EXCLUDED.intent_code,
    state_code = EXCLUDED.state_code,
    system_prompt = EXCLUDED.system_prompt,
    user_prompt = EXCLUDED.user_prompt,
    enabled = EXCLUDED.enabled;
