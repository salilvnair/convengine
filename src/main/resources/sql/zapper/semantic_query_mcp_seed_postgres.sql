-- Zapper semantic query MCP seed (Postgres)
-- Purpose: enable end-to-end natural-language -> db.semantic.query flow.

SET search_path TO v2, public;

-- -----------------------------------------------------------------------------
-- Cleanup (idempotent)
-- -----------------------------------------------------------------------------
DELETE FROM ce_rule WHERE intent_code = 'SEMANTIC_QUERY';
DELETE FROM ce_response WHERE intent_code = 'SEMANTIC_QUERY';
DELETE FROM ce_prompt_template WHERE intent_code = 'SEMANTIC_QUERY';
DELETE FROM ce_output_schema WHERE intent_code = 'SEMANTIC_QUERY';
DELETE FROM ce_intent_classifier WHERE intent_code = 'SEMANTIC_QUERY';
DELETE FROM ce_mcp_planner WHERE planner_id = 5301;
DELETE FROM ce_mcp_tool WHERE tool_code = 'db.semantic.query';
DELETE FROM ce_verbose WHERE intent_code = 'SEMANTIC_QUERY';
DELETE FROM ce_intent WHERE intent_code = 'SEMANTIC_QUERY';

-- -----------------------------------------------------------------------------
-- ce_config for semantic AST generator (CeConfigResolver-backed)
-- -----------------------------------------------------------------------------
WITH next_id AS (
  SELECT COALESCE(MAX(config_id), 0) AS max_id FROM ce_config
),
rows AS (
  SELECT 1 AS rn, 'SYSTEM_PROMPT'::text AS config_key, $$You are a semantic SQL AST planner.
Return JSON only.
Do not generate SQL text.
Use semantic field keys only.
Use where as boolean tree.
Use exists for existence checks and not_exists true for anti existence.
Use subquery_filters for scalar subquery comparisons.
Use windows only for ROW_NUMBER ranking when needed.$$::text AS config_value
  UNION ALL
  SELECT 2 AS rn, 'USER_PROMPT'::text AS config_key, $$Question: {{question}}
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
Context JSON: {{context_json}}$$::text AS config_value
  UNION ALL
  SELECT 3 AS rn, 'SCHEMA_PROMPT'::text AS config_key, $${
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
}$$::text AS config_value
)
INSERT INTO ce_config (config_id, config_type, config_key, config_value, enabled, created_at)
SELECT
  next_id.max_id + rows.rn,
  'DefaultSemanticAstGenerator',
  rows.config_key,
  rows.config_value,
  true,
  now()
FROM next_id
CROSS JOIN rows
ON CONFLICT (config_type, config_key)
DO UPDATE SET
  config_value = EXCLUDED.config_value,
  enabled = EXCLUDED.enabled,
  created_at = now();

-- -----------------------------------------------------------------------------
-- Intent + classifier
-- -----------------------------------------------------------------------------
INSERT INTO ce_intent (intent_code, description, priority, enabled, display_name, llm_hint)
VALUES
('SEMANTIC_QUERY', 'Run semantic query planning and SQL execution against Zapper schema', 45, true, 'Semantic Query',
 'Use semantic model + graph join path + AST generation to answer DB questions.');

INSERT INTO ce_intent_classifier (intent_code, state_code, rule_type, pattern, priority, enabled, description)
VALUES
('SEMANTIC_QUERY', 'UNKNOWN', 'REGEX',
 '(?i)\\b(disconnect|termination|downstream|request status|failed request|billbank|zapper|account status|join|sql|query)\\b',
 40, true,
 'Classifier for semantic query over zapper domain');

-- -----------------------------------------------------------------------------
-- Optional extraction schema
-- -----------------------------------------------------------------------------
INSERT INTO ce_output_schema (intent_code, state_code, json_schema, description, enabled, priority)
VALUES
(
  'SEMANTIC_QUERY',
  'ANALYZE',
  '{
    "type":"object",
    "properties":{
      "accountId":{"type":"string"},
      "requestId":{"type":"string"},
      "timeWindow":{"type":"string"}
    }
  }'::jsonb,
  'Optional fields for semantic query prompts',
  true,
  1
);

-- -----------------------------------------------------------------------------
-- Prompt template
-- -----------------------------------------------------------------------------
INSERT INTO ce_prompt_template
(intent_code, state_code, response_type, system_prompt, user_prompt, temperature, interaction_mode, interaction_contract, enabled)
VALUES
(
  'SEMANTIC_QUERY',
  'ANALYZE',
  'SCHEMA_JSON',
  'You extract structured fields for semantic DB querying. Return JSON only. Use only explicitly provided values from the latest user input.',
  'Latest user input:\n{{resolved_user_input}}\n\nExtract only supported schema fields if explicitly present. If none are present, return {}. Do not infer missing identifiers.',
  0.00,
  'COLLECT',
  '{"allows":["extract"],"expects":["structured_json"]}',
  true
),
(
  'SEMANTIC_QUERY',
  'ANALYZE',
  'DERIVED',
  'You are a semantic DB diagnostics assistant. Use MCP outputs only and avoid hallucinations.',
  'User input: {{user_input}}\nContext: {{context}}\nMCP: {{mcp_observations}}',
  0.00,
  'MCP',
  NULL,
  true
);

-- -----------------------------------------------------------------------------
-- Response config
-- -----------------------------------------------------------------------------
INSERT INTO ce_response
(intent_code, state_code, output_format, response_type, exact_text, derivation_hint, json_schema, priority, enabled, description)
VALUES
(
  'SEMANTIC_QUERY',
  'COMPLETED',
  'TEXT',
  'EXACT',
  $$[# th:if="${context != null and context.mcp != null and context.mcp.finalAnswer != null and !#strings.isEmpty(context.mcp.finalAnswer)}"][[${context.mcp.finalAnswer}]][/]
[# th:unless="${context != null and context.mcp != null and context.mcp.finalAnswer != null and !#strings.isEmpty(context.mcp.finalAnswer)}"]I could not find a finalized semantic answer for this request. Please retry with a more specific filter.[/]$$,
  NULL,
  NULL,
  10,
  true,
  'Semantic query completed response using EXACT thymeleaf fallback to MCP final answer'
),
(
  'SEMANTIC_QUERY',
  'FAILED',
  'TEXT',
  'DERIVED',
  NULL,
  'If context.mcp.lifecycle.errorMessage exists, explain the failure briefly and ask for refined query filters. Otherwise provide a safe retry message.',
  NULL,
  9,
  true,
  'Semantic query failure response derived from MCP lifecycle error details'
);

-- -----------------------------------------------------------------------------
-- Rules
-- -----------------------------------------------------------------------------
INSERT INTO ce_rule
(phase, intent_code, state_code, rule_type, match_pattern, "action", action_value, priority, enabled, description)
VALUES
('POST_AGENT_INTENT', 'SEMANTIC_QUERY', 'UNKNOWN', 'REGEX', '.*', 'SET_STATE', 'ANALYZE', 70, true,
 'Bootstrap SEMANTIC_QUERY into ANALYZE when classifier sets UNKNOWN'),
('POST_AGENT_INTENT', 'SEMANTIC_QUERY', 'IDLE', 'REGEX', '.*', 'SET_STATE', 'ANALYZE', 71, true,
 'Bootstrap SEMANTIC_QUERY into ANALYZE from IDLE'),
('POST_AGENT_MCP', 'SEMANTIC_QUERY', 'ANALYZE', 'JSON_PATH',
 '$[?(@.context.mcp.lifecycle.error==true || @.context.mcp.lifecycle.blocked==true || @.context.mcp.lifecycle.status == ''TOOL_ERROR'' || @.context.mcp.lifecycle.status == ''FALLBACK'' || @.context.mcp.lifecycle.outcome == ''ERROR'')]',
 'SET_STATE', 'FAILED', 72, true,
 'Move SEMANTIC_QUERY to FAILED when MCP lifecycle indicates error/blocked/fallback'),
('POST_AGENT_MCP', 'SEMANTIC_QUERY', 'ANALYZE', 'JSON_PATH',
 '$[?(@.context.mcp.finalAnswer != ''null'' && @.context.mcp.finalAnswer != null && @.context.mcp.finalAnswer != '''')]',
 'SET_STATE', 'COMPLETED', 73, true,
 'Move SEMANTIC_QUERY to COMPLETED when context.mcp.finalAnswer exists'),
('PRE_RESPONSE_RESOLUTION', 'SEMANTIC_QUERY', 'ANY', 'REGEX', '(?i)\\b(reset|restart|start over)\\b', 'SET_STATE', 'IDLE', 74, true,
 'Allow reset for semantic query flow');

-- -----------------------------------------------------------------------------
-- ce_verbose stage diagnostics for semantic pipeline
-- -----------------------------------------------------------------------------
INSERT INTO ce_verbose
(intent_code, state_code, step_match, step_value, determinant, rule_id, tool_code, message, error_message, priority, enabled, created_at)
VALUES
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticQueryRuntimeService', 'RUNTIME_ERROR', NULL, 'db.semantic.query',
 'Semantic runtime failed.',
 'Semantic runtime failed: [[${errorMessage}]]',
 4, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'DefaultSemanticAstGenerator', 'AST_INPUT', NULL, 'db.semantic.query',
 'Generating semantic AST using LLM.',
 'Failed before AST LLM generation.',
 11, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'DefaultSemanticAstGenerator', 'AST_OUTPUT', NULL, 'db.semantic.query',
 'Semantic AST generated by LLM for entity [[${entity}]].',
 'AST LLM output could not be parsed.',
 11, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'DefaultSemanticAstGenerator', 'AST_ERROR', NULL, 'db.semantic.query',
 'Semantic AST generation failed.',
 'Semantic AST LLM error: [[${errorMessage}]]',
 4, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticRetrievalStage', 'RETRIEVAL_DONE', NULL, 'db.semantic.query',
 'Semantic retrieval stage completed (entities=[[${candidateEntitiesCount}]], tables=[[${candidateTablesCount}]])',
 'Semantic retrieval stage failed.',
 11, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticSqlCompileStage', 'SQL_COMPILED', NULL, 'db.semantic.query',
 'Semantic SQL compile stage completed (params=[[${paramCount}]])',
 'Semantic SQL compile stage failed.',
 11, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticSqlExecuteStage', 'SQL_EXECUTED', NULL, 'db.semantic.query',
 'Semantic SQL execute stage completed (rows=[[${rowCount}]])',
 'Semantic SQL execute stage failed.',
 11, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticResultSummaryStage', 'RESULT_SUMMARIZED', NULL, 'db.semantic.query',
 'Semantic result summary stage completed.',
 'Semantic result summary stage failed.',
 11, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticJoinPathStage', 'JOIN_PATH_RESOLVED', NULL, 'db.semantic.query',
 'Join path resolved from [[${baseTable}]] (unresolved=[[${unresolvedTablesCount}]]).',
 'Join path resolution failed.',
 11, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticAstGenerationStage', 'AST_GENERATED', NULL, 'db.semantic.query',
 'AST generated for entity [[${entity}]] (filters=[[${filterCount}]], limit=[[${limit}]])',
 'AST generation stage failed.',
 11, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticAstValidationStage', 'AST_VALIDATED', NULL, 'db.semantic.query',
 'AST validation completed. valid=[[${valid}]] errorCount=[[${errorCount}]].',
 'AST validation failed: [[${errors}]]',
 4, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticRetrievalStage', 'RETRIEVAL_ERROR', NULL, 'db.semantic.query',
 'Semantic retrieval stage failed.',
 'Semantic retrieval stage failed: [[${errorMessage}]]',
 4, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticJoinPathStage', 'JOIN_PATH_ERROR', NULL, 'db.semantic.query',
 'Semantic join-path stage failed.',
 'Semantic join-path stage failed: [[${errorMessage}]]',
 4, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticAstGenerationStage', 'AST_GENERATION_ERROR', NULL, 'db.semantic.query',
 'Semantic AST generation stage failed.',
 'Semantic AST generation stage failed: [[${errorMessage}]]',
 4, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticAstValidationStage', 'AST_VALIDATION_ERROR', NULL, 'db.semantic.query',
 'Semantic AST validation stage failed.',
 'Semantic AST validation stage failed: [[${errorMessage}]]',
 4, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticSqlCompileStage', 'SQL_COMPILE_ERROR', NULL, 'db.semantic.query',
 'Semantic SQL compile stage failed.',
 'Semantic SQL compile stage failed: [[${errorMessage}]]',
 4, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticSqlExecuteStage', 'SQL_EXECUTE_ERROR', NULL, 'db.semantic.query',
 'Semantic SQL execute stage failed.',
 'Semantic SQL execute stage failed: [[${errorMessage}]]',
 4, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'SemanticResultSummaryStage', 'SUMMARY_ERROR', NULL, 'db.semantic.query',
 'Semantic summary stage failed.',
 'Semantic summary stage failed: [[${errorMessage}]]',
 4, true, now()),
('SEMANTIC_QUERY', 'ANALYZE', 'EXACT', 'McpToolStep', 'MCP_DUPLICATE_TOOL_CALL_SUPPRESSED', NULL, 'db.semantic.query',
 'Suppressed duplicate semantic tool call in same turn; answering from latest observation.',
 'Duplicate semantic tool call suppression failed.',
 4, true, now());

-- -----------------------------------------------------------------------------
-- MCP tool + planner
-- -----------------------------------------------------------------------------
INSERT INTO ce_mcp_tool (tool_id, tool_code, tool_group, intent_code, state_code, enabled, description)
VALUES
(9301, 'db.semantic.query', 'DB', 'SEMANTIC_QUERY', 'ANALYZE', true,
 'Execute semantic query pipeline (retrieval -> join path -> AST -> SQL -> execution)');

INSERT INTO ce_mcp_planner (planner_id, intent_code, state_code, system_prompt, user_prompt, enabled, created_at)
VALUES
(
  5301,
  'SEMANTIC_QUERY',
  'ANALYZE',
  'You are an MCP planning agent for semantic DB querying.
Rules:
1) Use CALL_TOOL db.semantic.query only when no semantic observation exists yet for the current user question.
2) If existing MCP observations already contain semantic summary or execution rows for the current question, return ANSWER.
3) Never return CALL_TOOL with the same tool_code and equivalent args more than once in the same turn.
4) Use ANSWER only for greetings/chitchat or when tool evidence is already sufficient.
5) When action is ANSWER and observations include rows, format as concise markdown table (header + rows).
Return strict JSON only.',
  'User input:\n{{user_input}}\n\nCurrent date/time context:\n- current_date: {{current_date}}\n- current_datetime: {{current_datetime}}\n- current_year: {{current_year}}\n- current_timezone: {{current_timezone}}\n\nStandalone query:\n{{standalone_query}}\n\nRecent conversation history:\n{{conversation_history}}\n\nContext JSON:\n{{context}}\n\nAvailable MCP tools:\n{{mcp_tools}}\n\nExisting MCP observations:\n{{mcp_observations}}\n\nReturn strict JSON:\n{\n  "action":"CALL_TOOL" | "ANSWER",\n  "tool_code":"<tool_code_or_null>",\n  "args":{},\n  "answer":"<text_or_null>"\n}',
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
