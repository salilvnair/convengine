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
{{user_input}}', 1);

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(13, 'DialogueActStep', 'SCHEMA_PROMPT', '{
  "type":"object",
  "required":["dialogueAct","confidence"],
  "properties":{
    "dialogueAct":{"type":"string","enum":["AFFIRM","NEGATE","EDIT","RESET","QUESTION","NEW_REQUEST","GREETING"]},
    "confidence":{"type":"number"}
  },
  "additionalProperties":false
}', 1);

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(14, 'DialogueActStep', 'QUERY_REWRITE_SYSTEM_PROMPT', ' You are a dialogue-act classifier and intelligent query search rewriter.
                        Using the conversation history, rewrite the user''s text into an explicit, standalone query that perfectly describes their intent without needing the conversation history context.
                        Also classify their dialogue act.
                        Return JSON only matching the exact schema.', 1);

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(15, 'DialogueActStep', 'QUERY_REWRITE_USER_PROMPT', '
 Conversation History:
 {{conversation_history}}

 User input:
 {{user_input}}
', 1);

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(16, 'DialogueActStep', 'QUERY_REWRITE_SCHEMA_JSON', '{
  "type":"object",
  "required":["dialogueAct","confidence","standaloneQuery"],
  "properties":{
    "dialogueAct":{"type":"string","enum":["AFFIRM","NEGATE","EDIT","RESET","QUESTION","NEW_REQUEST","GREETING"]},
    "confidence":{"type":"number"},
    "standaloneQuery":{"type":"string"}
  },
  "additionalProperties":false
}', 1);

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(17, 'DialogueActStep', 'REGEX_AFFIRM', '^(\\s)*(yes|yep|yeah|ok|okay|sure|go ahead|do that|please do|confirm|approved?)(\\s)*$', 1);

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(18, 'DialogueActStep', 'REGEX_NEGATE', '^(\\s)*(no|nope|nah|cancel|stop|don''t|do not)(\\s)*$', 1);

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(19, 'DialogueActStep', 'REGEX_EDIT', '^(\\s)*(edit|revise|change|modify|update)(\\s)*$', 1);

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(20, 'DialogueActStep', 'REGEX_RESET', '^(\\s)*(reset|restart|start over)(\\s)*$', 1);

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled)
VALUES(21, 'DialogueActStep', 'REGEX_GREETING', '^(\\s)*(hi|hello|hey|greetings|good morning|good afternoon|good evening|howdy)(\\s)*$', 1);

INSERT OR REPLACE INTO ce_verbose
(verbose_id, intent_code, state_code, step_match, step_value, determinant, rule_id, tool_code, message, error_message, priority, enabled)
VALUES
(1, 'ANY', 'ANY', 'REGEX', '.*Step$', 'STEP_ENTER', NULL, NULL, 'Agent is processing your request.', 'Failed while starting step execution.', 100, 1),
(2, 'ANY', 'ANY', 'REGEX', '.*Step$', 'STEP_EXIT', NULL, NULL, 'Step completed.', 'Step completed with issues.', 100, 1),
(3, 'ANY', 'ANY', 'EXACT', 'RulesStep', 'RULE_MATCH', NULL, NULL, 'Applying matching rule...', 'Rule evaluation failed.', 20, 1),
(4, 'ANY', 'ANY', 'EXACT', 'McpToolStep', 'MCP_TOOL_CALL', NULL, 'loan.credit.rating.check', 'Checking credit rating from credit union.', 'Unable to fetch credit rating at the moment.', 10, 1),
(5, 'ANY', 'ANY', 'EXACT', 'McpToolStep', 'MCP_TOOL_CALL', NULL, 'loan.credit.fraud.check', 'Running fraud verification.', 'Fraud verification failed. Please retry shortly.', 20, 1),
(6, 'ANY', 'ANY', 'EXACT', 'McpToolStep', 'MCP_TOOL_CALL', NULL, 'loan.debt.credit.summary', 'Analyzing debt-to-income and available credit.', 'Unable to fetch debt and credit summary right now.', 30, 1),
(7, 'ANY', 'ANY', 'EXACT', 'McpToolStep', 'MCP_TOOL_CALL', NULL, 'loan.application.submit', 'Submitting loan application.', 'Loan submission failed. Please retry in a few moments.', 40, 1),
(8, 'ANY', 'ANY', 'EXACT', 'McpToolStep', 'MCP_FINAL_ANSWER', NULL, NULL, 'Loan workflow completed.', 'Loan workflow hit an error.', 90, 1),
(9, 'ANY', 'ANY', 'REGEX', '.*Step$', 'STEP_ERROR', NULL, NULL, 'Step execution failed.', 'Step execution failed.', 5, 1),
(10, 'ANY', 'ANY', 'EXACT', 'AgentIntentResolver', 'AGENT_INTENT_START', NULL, NULL, 'Analyzing user intent...', 'Unable to start intent analysis.', 30, 1),
(11, 'ANY', 'ANY', 'EXACT', 'AgentIntentResolver', 'AGENT_INTENT_ACCEPTED', NULL, NULL, 'Intent resolved successfully.', 'Intent resolution failed.', 30, 1),
(12, 'ANY', 'ANY', 'EXACT', 'AgentIntentResolver', 'AGENT_INTENT_COLLISION', NULL, NULL, 'Intent ambiguity detected. Preparing clarification.', 'Intent disambiguation failed.', 20, 1),
(13, 'ANY', 'ANY', 'EXACT', 'AgentIntentResolver', 'AGENT_INTENT_NEEDS_CLARIFICATION', NULL, NULL, 'Clarification is required before proceeding.', 'Could not prepare clarification.', 20, 1),
(14, 'ANY', 'ANY', 'EXACT', 'AgentIntentResolver', 'AGENT_INTENT_REJECTED', NULL, NULL, 'Intent could not be finalized.', 'Intent resolution was rejected.', 10, 1),
(15, 'ANY', 'ANY', 'EXACT', 'RuleActionResolverFactory', 'RULE_ACTION_RESOLVER_SELECTED', NULL, NULL, 'Applying matched rule action.', 'Failed to apply rule action.', 25, 1),
(16, 'ANY', 'ANY', 'EXACT', 'RuleActionResolverFactory', 'RULE_ACTION_RESOLVER_NOT_FOUND', NULL, NULL, 'No rule action resolver found for this action.', 'No rule action resolver found.', 10, 1),
(17, 'ANY', 'ANY', 'EXACT', 'ResponseTypeResolverFactory', 'RESPONSE_TYPE_RESOLVER_SELECTED', NULL, NULL, 'Selected response strategy.', 'Unable to select response strategy.', 25, 1),
(18, 'ANY', 'ANY', 'EXACT', 'ResponseTypeResolverFactory', 'RESPONSE_TYPE_RESOLVER_NOT_FOUND', NULL, NULL, 'No response strategy matched.', 'No response type resolver found.', 10, 1),
(19, 'ANY', 'ANY', 'EXACT', 'OutputFormatResolverFactory', 'OUTPUT_FORMAT_RESOLVER_SELECTED', NULL, NULL, 'Selected response output formatter.', 'Unable to select output formatter.', 25, 1),
(20, 'ANY', 'ANY', 'EXACT', 'OutputFormatResolverFactory', 'OUTPUT_FORMAT_RESOLVER_NOT_FOUND', NULL, NULL, 'No output formatter matched.', 'No output format resolver found.', 10, 1);
