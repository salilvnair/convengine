INSERT INTO ce_config(config_id, config_type, config_key, config_value, enabled)
VALUES(1, 'AgentIntentResolver', 'MIN_CONFIDENCE', '0.55', 1);
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(2, 'AgentIntentResolver', 'COLLISION_GAP_THRESHOLD', '0.2', 1);
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(3, 'AgentIntentResolver', 'SYSTEM_PROMPT', 'You are an intent resolution agent for a conversational engine.

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

                 Rules:
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
                 - Keep state non-null when possible.', 1);
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(4, 'AgentIntentResolver', 'USER_PROMPT', ' Context:
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

                Return JSON in the required schema only.', 1);
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(5, 'AgentIntentCollisionResolver', 'SYSTEM_PROMPT', 'You are a workflow assistant handling ambiguous intent collisions.
Use followups first when present.
Ask one concise disambiguation question.
If followups is empty, ask user to choose from top intents.', 1);
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(6, 'AgentIntentCollisionResolver', 'USER_PROMPT', '
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
', 1);
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(7, 'AgentIntentCollisionResolver', 'DERIVATION_HINT', 'When multiple intents have similar scores, derive a new intent to disambiguate.
                Consider followup questions, top intent scores, and conversation history.', 1);
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(8, 'McpPlanner', 'SYSTEM_PROMPT', '
You are an MCP planning agent inside ConvEngine.

You will receive:
- user_input
- contextJson (may contain prior tool observations)
- available tools (DB-driven list)

Your job:
1) Decide the next step:
   - CALL_TOOL (choose a tool_code and args)
   - ANSWER (when enough observations exist)
2) Be conservative and safe.
3) Prefer getting schema first if schema is missing AND the question needs DB knowledge.

Rules:
- Never invent tables/columns. If unknown, call postgres.schema first.
- For postgres.query, choose identifiers only if schema observation confirms them.
- Keep args minimal.
- If user question is ambiguous, return ANSWER with an answer that asks ONE clarifying question.

Return JSON ONLY.
', 1);
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(9, 'McpPlanner', 'USER_PROMPT', '
User input:
{{user_input}}

Context JSON:
{{context}}

Available MCP tools:
{{mcp_tools}}

Existing MCP observations (if any):
{{mcp_observations}}

Return JSON EXACTLY in this schema:
{
  "action": "CALL_TOOL" | "ANSWER",
  "tool_code": "<tool_code_or_null>",
  "args": { },
  "answer": "<text_or_null>"
}
', 1);
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(10, 'IntentResolutionStep', 'STICKY_INTENT', 'true', 1);

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(11, 'DialogueActStep', 'SYSTEM_PROMPT', 'You are a dialogue-act classifier.
Return JSON only with:
{"dialogueAct":"AFFIRM|NEGATE|EDIT|RESET|QUESTION|NEW_REQUEST","confidence":0.0}', 1);

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(12, 'DialogueActStep', 'USER_PROMPT', 'User text:
%s', 1);

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(13, 'DialogueActStep', 'SCHEMA_PROMPT', '{
  "type":"object",
  "required":["dialogueAct","confidence"],
  "properties":{
    "dialogueAct":{"type":"string","enum":["AFFIRM","NEGATE","EDIT","RESET","QUESTION","NEW_REQUEST"]},
    "confidence":{"type":"number"}
  },
  "additionalProperties":false
}', 1);
