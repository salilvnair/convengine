-- ConvEngine guardrail hardening for classifier-first FAQ flow.
-- Focus:
-- 1) Tight FAQ prompt boundaries (for /faq classifier path)
-- 2) Add PRE + POST response ce_rule hooks to force UNKNOWN when off-domain
-- 3) Keep UNKNOWN response deterministic (EXACT)
-- 4) Raise intent confidence threshold to reduce weak routing

-- 1) More conservative confidence floor for intent routing.
UPDATE ce_config
SET config_value = '0.70'
WHERE config_type = 'AgentIntentResolver'
  AND config_key = 'MIN_CONFIDENCE';

-- 2) Restrict FAQ prompts to One Click Disconnect domain.
UPDATE ce_prompt_template
SET system_prompt = 'You are the FAQ assistant for the One Click Disconnect tool.
Only answer questions related to electricity disconnection workflows, required request details, request status, and supported operational steps in this tool.
If the user asks anything outside this domain (math, trivia, generic knowledge, coding help, unrelated topics), do not answer that question.
Return strict JSON with:
{
  "answer": "I''m sorry, I can only help with One Click Disconnect questions.",
  "confidence": 0.0,
  "matchedFaqIds": []
}
Do not include extra keys or prose outside JSON.'
WHERE intent_code = 'FAQ'
  AND enabled = true;

-- 3) Ensure UNKNOWN does not call LLM.
UPDATE ce_response
SET response_type = 'EXACT',
    exact_text = 'Sorry, I can help only with One Click Disconnect queries. Please ask about electricity disconnection requests.',
    derivation_hint = NULL
WHERE intent_code = 'UNKNOWN'
  AND enabled = true;

-- 4) PRE_RESPONSE guardrail:
-- if FAQ is selected without container data, route to UNKNOWN before any derived LLM call.
INSERT INTO ce_rule (phase, intent_code, state_code, rule_type, match_pattern, action, action_value, priority, enabled, description)
SELECT
    'PRE_RESPONSE_RESOLUTION',
    'FAQ',
    'ANY',
    'JSONPATH',
    '$[?(@.intent == ''FAQ'' && @.hasContainerData == false)]',
    'SET_STATE',
    'UNKNOWN',
    5,
    true,
    'FAQ guardrail: missing container data routes to UNKNOWN before response resolution'
WHERE NOT EXISTS (
    SELECT 1
    FROM ce_rule
    WHERE phase = 'PRE_RESPONSE_RESOLUTION'
      AND intent_code = 'FAQ'
      AND action = 'SET_STATE'
      AND action_value = 'UNKNOWN'
      AND match_pattern = '$[?(@.intent == ''FAQ'' && @.hasContainerData == false)]'
);

-- 5) POST_RESPONSE guardrail:
-- inspect resolved payload and correct state to UNKNOWN when FAQ match is empty/low-confidence.
INSERT INTO ce_rule (phase, intent_code, state_code, rule_type, match_pattern, action, action_value, priority, enabled, description)
SELECT
    'POST_RESPONSE_RESOLUTION',
    'FAQ',
    'ANY',
    'JSONPATH',
    '$[?(@.intent == ''FAQ'' && ((@.payload.matchedFaqIds empty true) || (@.payload.confidence != null && @.payload.confidence <= 0.01)))]',
    'SET_STATE',
    'UNKNOWN',
    5,
    true,
    'FAQ guardrail: force UNKNOWN when post-resolution FAQ payload indicates off-domain match'
WHERE NOT EXISTS (
    SELECT 1
    FROM ce_rule
    WHERE phase = 'POST_RESPONSE_RESOLUTION'
      AND intent_code = 'FAQ'
      AND action = 'SET_STATE'
      AND action_value = 'UNKNOWN'
      AND match_pattern = '$[?(@.intent == ''FAQ'' && ((@.payload.matchedFaqIds empty true) || (@.payload.confidence != null && @.payload.confidence <= 0.01)))]'
);
