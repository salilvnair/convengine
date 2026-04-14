-- One-time cleanup for semantic v2 LLM-only mode.
-- Removes legacy knowledge/dbkg/resolve/v1-ast runtime rows while preserving active v2 flow:
-- db.semantic.interpret -> db.semantic.query -> postgres.query

BEGIN;

-- 1) Disable/remove legacy MCP tools.
DELETE FROM ce_mcp_db_tool
WHERE tool_code IN ('db.semantic.catalog', 'db.semantic.resolve')
   OR tool_code LIKE 'dbkg.%';

DELETE FROM ce_mcp_tool
WHERE tool_code IN ('db.semantic.catalog', 'db.semantic.resolve')
   OR tool_code LIKE 'dbkg.%';

-- 2) Remove legacy semantic-v1 / deterministic / dbkg prompt+config rows.
DELETE FROM ce_config
WHERE config_type IN (
    'DefaultSemanticAstGenerator',
    'SemanticQueryRuntimeService',
    'SemanticResolveService',
    'SemanticQueryV2Service',
    'DbSemanticCatalogService',
    'DbkgSupportService'
);

-- 3) Remove legacy semantic verbose/audit routing rows (keep interpret/query/postgres rows).
DELETE FROM ce_verbose
WHERE intent_code = 'SEMANTIC_QUERY'
  AND (
       tool_code IN ('db.semantic.resolve', 'db.semantic.catalog')
       OR tool_code LIKE 'dbkg.%'
       OR step_value IN (
            'SemanticResolveService',
            'SemanticQueryRuntimeService',
            'DefaultSemanticAstGenerator',
            'SemanticResolveStage',
            'SemanticAstGenerationStage',
            'SemanticAstValidationStage',
            'SemanticSqlCompileStage',
            'SemanticJoinPathStage'
       )
       OR determinant LIKE 'AST_%'
       OR determinant IN ('RESOLVE_DONE', 'QUERY_COMPILED')
  );

-- 4) Hard-set planner prompts to v2 chain only (no resolve/catalog/dbkg).
UPDATE ce_mcp_planner
SET system_prompt =
'You are an MCP planning agent for semantic v2 DB querying.\nUse exact chain:\n1) db.semantic.interpret\n2) db.semantic.query\n3) postgres.query\nIf interpret/query says needsClarification=true, stop and ANSWER with clarificationQuestion.\nDo not skip or reorder tools.\nReturn strict JSON only.\n`action` MUST be exactly CALL_TOOL or ANSWER.\nNever return clarification_required / needs_clarification / clarify.',
    user_prompt =
'User input:\n{{user_input}}\n\nStandalone query:\n{{standalone_query}}\n\nMCP:\n{{context.mcp}}\n\nAvailable tools:\n{{mcp_tools}}\n\nExisting MCP observations:\n{{mcp_observations}}\n\nReturn strict JSON:\n{\n  "action":"CALL_TOOL" | "ANSWER",\n  "tool_code":"<tool_code_or_null>",\n  "args":{},\n  "answer":"<text_or_null>",\n  "operation_tag":"<POLICY_RESTRICTED_OPERATION_or_null>"\n}\n`action` MUST be exactly CALL_TOOL or ANSWER. No other value is allowed.'
WHERE intent_code = 'SEMANTIC_QUERY'
  AND state_code = 'ANALYZE';

COMMIT;
