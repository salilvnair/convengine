INSERT INTO ce_config(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(1, 'AgentIntentResolver', 'MIN_CONFIDENCE', '0.55', true, '2026-02-10 10:15:54.227');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(2, 'AgentIntentResolver', 'COLLISION_GAP_THRESHOLD', '0.2', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
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
                 - Keep state non-null when possible.', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
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

                Return JSON in the required schema only.', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(5, 'AgentIntentCollisionResolver', 'SYSTEM_PROMPT', 'You are a workflow assistant handling ambiguous intent collisions.
Use followups first when present.
Ask one concise disambiguation question.
If followups is empty, ask user to choose from top intents.', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
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
', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(7, 'AgentIntentCollisionResolver', 'DERIVATION_HINT', 'When multiple intents have similar scores, derive a new intent to disambiguate.
                Consider followup questions, top intent scores, and conversation history.', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
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
', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
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
', true, '2026-02-10 10:15:54.230');
INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(10, 'IntentResolutionStep', 'STICKY_INTENT', 'true', true, '2026-02-17 10:15:54.230');

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(11, 'DialogueActStep', 'SYSTEM_PROMPT', 'You are a dialogue-act classifier.
Return JSON only with:
{"dialogueAct":"AFFIRM|NEGATE|EDIT|RESET|QUESTION|NEW_REQUEST","confidence":0.0}', true, '2026-02-20 10:15:54.230');

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(12, 'DialogueActStep', 'USER_PROMPT', 'User text:
{{user_input}}', true, '2026-02-20 10:15:54.230');

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(13, 'DialogueActStep', 'SCHEMA_PROMPT', '{
  "type":"object",
  "required":["dialogueAct","confidence"],
  "properties":{
    "dialogueAct":{"type":"string","enum":["AFFIRM","NEGATE","EDIT","RESET","QUESTION","NEW_REQUEST","GREETING"]},
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
    "dialogueAct":{"type":"string","enum":["AFFIRM","NEGATE","EDIT","RESET","QUESTION","NEW_REQUEST","GREETING"]},
    "confidence":{"type":"number"},
    "standaloneQuery":{"type":"string"}
  },
  "additionalProperties":false
}', true, '2026-02-20 10:15:54.230');

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(17, 'DialogueActStep', 'REGEX_AFFIRM', '^(\\s)*(yes|yep|yeah|ok|okay|sure|go ahead|do that|please do|confirm|approved?)(\\s)*$', true, '2026-02-24 10:15:54.230');

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(18, 'DialogueActStep', 'REGEX_NEGATE', '^(\\s)*(no|nope|nah|cancel|stop|don''t|do not)(\\s)*$', true, '2026-02-24 10:15:54.230');

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(19, 'DialogueActStep', 'REGEX_EDIT', '^(\\s)*(edit|revise|change|modify|update)(\\s)*$', true, '2026-02-24 10:15:54.230');

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(20, 'DialogueActStep', 'REGEX_RESET', '^(\\s)*(reset|restart|start over)(\\s)*$', true, '2026-02-24 10:15:54.230');

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(21, 'DialogueActStep', 'REGEX_GREETING', '^(\\s)*(hi|hello|hey|greetings|good morning|good afternoon|good evening|howdy)(\\s)*$', true, '2026-02-24 10:15:54.230');

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(24, 'DefaultSemanticAstGenerator', 'SYSTEM_PROMPT', 'You are a semantic SQL AST planner.
Return JSON only.
Do not generate SQL text.
Use semantic field keys only.
Use where as boolean tree.
Use exists for existence checks and not_exists true for anti existence.
Use subquery_filters for scalar subquery comparisons.
Use windows only for ROW_NUMBER ranking when needed.', true, '2026-03-04 10:15:54.230');

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(25, 'DefaultSemanticAstGenerator', 'USER_PROMPT', 'Question: {{question}}
Selected entity: {{selected_entity}}
Selected entity description: {{selected_entity_description}}
Allowed fields for selected entity: {{selected_entity_fields_json}}
Allowed entities: {{allowed_entities}}
Candidate entities: {{candidate_entities_json}}
Candidate tables: {{candidate_tables_json}}
Join path: {{join_path_json}}
Guidance:
- Prefer where for filters.
- Use exists when asking has or has not related records.
- Use subquery_filters for compare with subquery result.
- Use windows for ranking only.
Context JSON: {{context_json}}', true, '2026-03-04 10:15:54.230');

INSERT INTO ce_config
(config_id, config_type, config_key, config_value, enabled, created_at)
VALUES(26, 'DefaultSemanticAstGenerator', 'SCHEMA_PROMPT', '{
  "type":"object",
  "additionalProperties":false,
  "required":["astVersion","entity","select","projections","filters","where","exists","subquery_filters","sort","group_by","metrics","windows","having","limit","offset","distinct","join_hints"],
  "properties":{
    "astVersion":{"type":"string","enum":["v1"]},
    "entity":{"type":"string"},
    "select":{"type":"array","items":{"type":"string"}},
    "projections":{
      "type":"array",
      "items":{"type":"object","additionalProperties":false,"required":["field","alias"],"properties":{"field":{"type":"string"},"alias":{"type":["string","null"]}}}
    },
    "filters":{
      "type":"array",
      "items":{
        "type":"object",
        "additionalProperties":false,
        "required":["field","op","value"],
        "properties":{
          "field":{"type":"string"},
          "op":{"type":"string"},
          "value":{"type":["string","number","integer","boolean","null","array"],"items":{"type":["string","number","integer","boolean","null"]}}
        }
      }
    },
    "where":{"$ref":"#/$defs/filter_group"},
    "exists":{
      "type":"array",
      "items":{"type":"object","additionalProperties":false,"required":["entity","where","not_exists"],"properties":{"entity":{"type":"string"},"where":{"$ref":"#/$defs/filter_group"},"not_exists":{"type":"boolean"}}}
    },
    "subquery_filters":{
      "type":"array",
      "items":{
        "type":"object",
        "additionalProperties":false,
        "required":["field","op","subquery"],
        "properties":{
          "field":{"type":"string"},
          "op":{"type":"string"},
          "subquery":{
            "type":"object",
            "additionalProperties":false,
            "required":["entity","select_field","where","group_by","having","limit"],
            "properties":{
              "entity":{"type":"string"},
              "select_field":{"type":"string"},
              "where":{"$ref":"#/$defs/filter_group"},
              "group_by":{"type":"array","items":{"type":"string"}},
              "having":{"$ref":"#/$defs/filter_group"},
              "limit":{"type":"integer"}
            }
          }
        }
      }
    },
    "sort":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["field","direction","nulls"],"properties":{"field":{"type":"string"},"direction":{"type":"string","enum":["ASC","DESC"]},"nulls":{"type":["string","null"],"enum":["FIRST","LAST",null]}}}},
    "group_by":{"type":"array","items":{"type":"string"}},
    "metrics":{"type":"array","items":{"type":"string"}},
    "windows":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["name","function","partition_by","order_by"],"properties":{"name":{"type":["string","null"]},"function":{"type":"string","enum":["ROW_NUMBER"]},"partition_by":{"type":"array","items":{"type":"string"}},"order_by":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["field","direction","nulls"],"properties":{"field":{"type":"string"},"direction":{"type":"string","enum":["ASC","DESC"]},"nulls":{"type":["string","null"],"enum":["FIRST","LAST",null]}}}}}}},
    "having":{"$ref":"#/$defs/filter_group"},
    "limit":{"type":"integer"},
    "offset":{"type":"integer"},
    "distinct":{"type":"boolean"},
    "join_hints":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["leftField","rightField","joinType"],"properties":{"leftField":{"type":"string"},"rightField":{"type":"string"},"joinType":{"type":"string"}}}}
  },
  "$defs":{
    "filter_group":{
      "type":"object",
      "additionalProperties":false,
      "required":["op","conditions","groups"],
      "properties":{
        "op":{"type":"string","enum":["AND","OR","NOT"]},
        "conditions":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["field","op","value"],"properties":{"field":{"type":"string"},"op":{"type":"string"},"value":{"type":["string","number","integer","boolean","null","array"],"items":{"type":["string","number","integer","boolean","null"]}}}}},
        "groups":{"type":"array","items":{"$ref":"#/$defs/filter_group"}}
      }
    }
  }
}', true, '2026-03-04 10:15:54.230');

INSERT INTO ce_verbose
(verbose_id, intent_code, state_code, step_match, step_value, determinant, rule_id, tool_code, message, error_message, priority, enabled, created_at)
VALUES
(1, 'ANY', 'ANY', 'REGEX', '.*Step$', 'STEP_ENTER', NULL, NULL, 'Agent is processing your request.', 'Failed while starting step execution.', 100, true, CURRENT_TIMESTAMP),
(2, 'ANY', 'ANY', 'REGEX', '.*Step$', 'STEP_EXIT', NULL, NULL, 'Step completed.', 'Step completed with issues.', 100, true, CURRENT_TIMESTAMP),
(3, 'ANY', 'ANY', 'EXACT', 'RulesStep', 'RULE_MATCH', NULL, NULL, 'Applying matching rule...', 'Rule evaluation failed.', 20, true, CURRENT_TIMESTAMP),
(4, 'ANY', 'ANY', 'EXACT', 'McpToolStep', 'MCP_TOOL_CALL', NULL, 'loan.credit.rating.check', 'Checking credit rating from credit union.', 'Unable to fetch credit rating at the moment.', 10, true, CURRENT_TIMESTAMP),
(5, 'ANY', 'ANY', 'EXACT', 'McpToolStep', 'MCP_TOOL_CALL', NULL, 'loan.credit.fraud.check', 'Running fraud verification.', 'Fraud verification failed. Please retry shortly.', 20, true, CURRENT_TIMESTAMP),
(6, 'ANY', 'ANY', 'EXACT', 'McpToolStep', 'MCP_TOOL_CALL', NULL, 'loan.debt.credit.summary', 'Analyzing debt-to-income and available credit.', 'Unable to fetch debt and credit summary right now.', 30, true, CURRENT_TIMESTAMP),
(7, 'ANY', 'ANY', 'EXACT', 'McpToolStep', 'MCP_TOOL_CALL', NULL, 'loan.application.submit', 'Submitting loan application.', 'Loan submission failed. Please retry in a few moments.', 40, true, CURRENT_TIMESTAMP),
(8, 'ANY', 'ANY', 'EXACT', 'McpToolStep', 'MCP_FINAL_ANSWER', NULL, NULL, 'Loan workflow completed.', 'Loan workflow hit an error.', 90, true, CURRENT_TIMESTAMP),
(9, 'ANY', 'ANY', 'REGEX', '.*Step$', 'STEP_ERROR', NULL, NULL, 'Step execution failed.', 'Step execution failed.', 5, true, CURRENT_TIMESTAMP),
(10, 'ANY', 'ANY', 'EXACT', 'AgentIntentResolver', 'AGENT_INTENT_START', NULL, NULL, 'Analyzing user intent...', 'Unable to start intent analysis.', 30, true, CURRENT_TIMESTAMP),
(11, 'ANY', 'ANY', 'EXACT', 'AgentIntentResolver', 'AGENT_INTENT_ACCEPTED', NULL, NULL, 'Intent resolved successfully.', 'Intent resolution failed.', 30, true, CURRENT_TIMESTAMP),
(12, 'ANY', 'ANY', 'EXACT', 'AgentIntentResolver', 'AGENT_INTENT_COLLISION', NULL, NULL, 'Intent ambiguity detected. Preparing clarification.', 'Intent disambiguation failed.', 20, true, CURRENT_TIMESTAMP),
(13, 'ANY', 'ANY', 'EXACT', 'AgentIntentResolver', 'AGENT_INTENT_NEEDS_CLARIFICATION', NULL, NULL, 'Clarification is required before proceeding.', 'Could not prepare clarification.', 20, true, CURRENT_TIMESTAMP),
(14, 'ANY', 'ANY', 'EXACT', 'AgentIntentResolver', 'AGENT_INTENT_REJECTED', NULL, NULL, 'Intent could not be finalized.', 'Intent resolution was rejected.', 10, true, CURRENT_TIMESTAMP),
(15, 'ANY', 'ANY', 'EXACT', 'RuleActionResolverFactory', 'RULE_ACTION_RESOLVER_SELECTED', NULL, NULL, 'Applying matched rule action.', 'Failed to apply rule action.', 25, true, CURRENT_TIMESTAMP),
(16, 'ANY', 'ANY', 'EXACT', 'RuleActionResolverFactory', 'RULE_ACTION_RESOLVER_NOT_FOUND', NULL, NULL, 'No rule action resolver found for this action.', 'No rule action resolver found.', 10, true, CURRENT_TIMESTAMP),
(17, 'ANY', 'ANY', 'EXACT', 'ResponseTypeResolverFactory', 'RESPONSE_TYPE_RESOLVER_SELECTED', NULL, NULL, 'Selected response strategy.', 'Unable to select response strategy.', 25, true, CURRENT_TIMESTAMP),
(18, 'ANY', 'ANY', 'EXACT', 'ResponseTypeResolverFactory', 'RESPONSE_TYPE_RESOLVER_NOT_FOUND', NULL, NULL, 'No response strategy matched.', 'No response type resolver found.', 10, true, CURRENT_TIMESTAMP),
(19, 'ANY', 'ANY', 'EXACT', 'OutputFormatResolverFactory', 'OUTPUT_FORMAT_RESOLVER_SELECTED', NULL, NULL, 'Selected response output formatter.', 'Unable to select output formatter.', 25, true, CURRENT_TIMESTAMP),
(20, 'ANY', 'ANY', 'EXACT', 'OutputFormatResolverFactory', 'OUTPUT_FORMAT_RESOLVER_NOT_FOUND', NULL, NULL, 'No output formatter matched.', 'No output format resolver found.', 10, true, CURRENT_TIMESTAMP),
(21, 'ANY', 'ANY', 'EXACT', 'McpDbExecutor', 'MCP_DB_SQL_EXECUTION', NULL, NULL, 'Executing MCP database query.', 'MCP database query failed.', 15, true, CURRENT_TIMESTAMP),
(22, 'ANY', 'ANY', 'EXACT', 'DbkgQueryTemplateStepExecutor', 'DBKG_QUERY_SQL_EXECUTION', NULL, NULL, 'Running Database Knowledge Graph query template.', 'Database Knowledge Graph query template failed.', 15, true, CURRENT_TIMESTAMP);
