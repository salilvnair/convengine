
-- ============================================================
-- 1) INTENT
-- ============================================================

INSERT INTO ce_intent (
    intent_code,
    description,
    priority,
    enabled,
    display_name,
    llm_hint
)
VALUES (
           'DBKG_DIAGNOSTICS',
           'Routes a user into the Database Knowledge Graph (DBKG) diagnostic flow for Zapper production investigations.',
           20,
           TRUE,
           'DBKG Diagnostics',
           'Use when the user asks a production-debug question about disconnects, inventory mismatches, billing gaps, or Zapper system behavior.'
       )
    ON CONFLICT (intent_code) DO UPDATE
                                     SET
                                         description  = EXCLUDED.description,
                                     priority     = EXCLUDED.priority,
                                     enabled      = EXCLUDED.enabled,
                                     display_name = EXCLUDED.display_name,
                                     llm_hint     = EXCLUDED.llm_hint;


-- ============================================================
-- 2) INTENT CLASSIFIER
-- ============================================================

INSERT INTO ce_intent_classifier (
    intent_code,
    state_code,
    rule_type,
    pattern,
    priority,
    enabled,
    description
)
SELECT
    'DBKG_DIAGNOSTICS',
    'UNKNOWN',
    'REGEX',
    '(?i).*(disconnect|inventory not found|billbank|zapper|zpdisconnectid|assigned|rejected|order service|billing gap).*',
    10,
    TRUE,
    'Routes Zapper diagnostic questions into the DBKG flow.'
    WHERE NOT EXISTS (
    SELECT 1
    FROM ce_intent_classifier
    WHERE intent_code = 'DBKG_DIAGNOSTICS'
      AND state_code  = 'UNKNOWN'
      AND rule_type   = 'REGEX'
);


-- ============================================================
-- 3) OUTPUT SCHEMA
-- ============================================================

UPDATE ce_output_schema
SET json_schema = '{
      "type": "object",
      "required": ["accountId", "accLocId"],
      "properties": {
        "playbookCode": {"type": "string"},
        "zp_request_id": {"type": "string"},
        "zp_connection_id": {"type": "string"},
        "accountId": {"type": "string"},
        "accLocId": {"type": "string"},
        "zpDisconnectId": {"type": "string"},
        "customerName": {"type": "string"},
        "customerZip": {"type": "string"},
        "contactNumber": {"type": "string"},
        "hours": {"type": "integer"}
      }
    }'::jsonb,
    description = 'Structured investigation fields used by the DBKG diagnostic flow.',
    enabled = TRUE,
    priority = 100
WHERE intent_code = 'DBKG_DIAGNOSTICS'
  AND state_code = 'ANALYZE';

INSERT INTO ce_output_schema (
    intent_code,
    state_code,
    json_schema,
    description,
    enabled,
    priority
)
SELECT
    'DBKG_DIAGNOSTICS',
    'ANALYZE',
    '{
      "type": "object",
      "required": ["accountId", "accLocId"],
      "properties": {
        "playbookCode": {"type": "string"},
        "zp_request_id": {"type": "string"},
        "zp_connection_id": {"type": "string"},
        "accountId": {"type": "string"},
        "accLocId": {"type": "string"},
        "zpDisconnectId": {"type": "string"},
        "customerName": {"type": "string"},
        "customerZip": {"type": "string"},
        "contactNumber": {"type": "string"},
        "hours": {"type": "integer"}
      }
    }'::jsonb,
    'Structured investigation fields used by the DBKG diagnostic flow.',
    TRUE,
    100
    WHERE NOT EXISTS (
    SELECT 1
    FROM ce_output_schema
    WHERE intent_code = 'DBKG_DIAGNOSTICS'
      AND state_code  = 'ANALYZE'
);


-- ============================================================
-- 4) PROMPT TEMPLATES
-- ============================================================

-- ANALYZE
INSERT INTO ce_prompt_template (
    intent_code,
    state_code,
    response_type,
    system_prompt,
    user_prompt,
    temperature,
    interaction_mode,
    interaction_contract,
    enabled
)
SELECT
    'DBKG_DIAGNOSTICS',
    'ANALYZE',
    'EXACT',
    'You are a production support assistant for Zapper.',
    'If the user has not given enough identifying detail, ask only for the missing identifiers needed to investigate the issue.',
    0.00,
    'COLLECT',
    '{"allows":["ask_follow_up","clarify"],"expects":["structured_input"]}',
    TRUE
    WHERE NOT EXISTS (
    SELECT 1
    FROM ce_prompt_template
    WHERE intent_code = 'DBKG_DIAGNOSTICS'
      AND state_code  = 'ANALYZE'
      AND response_type = 'EXACT'
);

-- COMPLETED
INSERT INTO ce_prompt_template (
    intent_code,
    state_code,
    response_type,
    system_prompt,
    user_prompt,
    temperature,
    interaction_mode,
    interaction_contract,
    enabled
)
SELECT
    'DBKG_DIAGNOSTICS',
    'COMPLETED',
    'DERIVED',
    'You are a production support assistant for Zapper.',
    'Convert the final DBKG conclusion into a concise operator-facing answer using context.mcp.finalAnswer.',
    0.00,
    'FINAL',
    '{"allows":["summarize"],"expects":["mcp_final_answer"]}',
    TRUE
    WHERE NOT EXISTS (
    SELECT 1
    FROM ce_prompt_template
    WHERE intent_code = 'DBKG_DIAGNOSTICS'
      AND state_code  = 'COMPLETED'
      AND response_type = 'DERIVED'
);


-- ============================================================
-- 5) RESPONSES
-- ============================================================

-- ANALYZE
INSERT INTO ce_response (
    intent_code,
    state_code,
    output_format,
    response_type,
    exact_text,
    derivation_hint,
    json_schema,
    priority,
    enabled,
    description
)
SELECT
    'DBKG_DIAGNOSTICS',
    'ANALYZE',
    'TEXT',
    'EXACT',
    'Share the key identifiers you have, such as zp_request_id, zp_connection_id, accountId, accLocId, or zpDisconnectId.',
    NULL,
    NULL,
    10,
    TRUE,
    'Fallback follow-up response during ANALYZE.'
    WHERE NOT EXISTS (
    SELECT 1
    FROM ce_response
    WHERE intent_code = 'DBKG_DIAGNOSTICS'
      AND state_code  = 'ANALYZE'
      AND response_type = 'EXACT'
);

-- COMPLETED
INSERT INTO ce_response (
    intent_code,
    state_code,
    output_format,
    response_type,
    exact_text,
    derivation_hint,
    json_schema,
    priority,
    enabled,
    description
)
SELECT
    'DBKG_DIAGNOSTICS',
    'COMPLETED',
    'TEXT',
    'DERIVED',
    NULL,
    'Render final answer from context.mcp.finalAnswer.',
    NULL,
    10,
    TRUE,
    'Final DBKG response.'
    WHERE NOT EXISTS (
    SELECT 1
    FROM ce_response
    WHERE intent_code = 'DBKG_DIAGNOSTICS'
      AND state_code  = 'COMPLETED'
      AND response_type = 'DERIVED'
);


-- ============================================================
-- 6) RULES
-- ============================================================

-- UNKNOWN → ANALYZE
INSERT INTO ce_rule (
    phase,
    intent_code,
    state_code,
    rule_type,
    match_pattern,
    "action",
    action_value,
    priority,
    enabled,
    description
)
SELECT
    'POST_AGENT_INTENT',
    'DBKG_DIAGNOSTICS',
    'UNKNOWN',
    'EXACT',
    'ANY',
    'SET_STATE',
    'ANALYZE',
    10,
    TRUE,
    'Enter ANALYZE state.'
    WHERE NOT EXISTS (
    SELECT 1
    FROM ce_rule
    WHERE phase = 'POST_AGENT_INTENT'
      AND intent_code = 'DBKG_DIAGNOSTICS'
      AND state_code = 'UNKNOWN'
);

-- ANALYZE → COMPLETED
INSERT INTO ce_rule (
    phase,
    intent_code,
    state_code,
    rule_type,
    match_pattern,
    "action",
    action_value,
    priority,
    enabled,
    description
)
SELECT
    'POST_AGENT_MCP',
    'DBKG_DIAGNOSTICS',
    'ANALYZE',
    'EXACT',
    'ANY',
    'SET_STATE',
    'COMPLETED',
    10,
    TRUE,
    'Close DBKG flow after MCP.'
    WHERE NOT EXISTS (
    SELECT 1
    FROM ce_rule
    WHERE phase = 'POST_AGENT_MCP'
      AND intent_code = 'DBKG_DIAGNOSTICS'
      AND state_code = 'ANALYZE'
);
