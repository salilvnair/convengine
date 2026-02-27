-- PostgreSQL standalone seed script for ce_verbose.
-- Assumes ce_verbose exists with the latest schema.

DELETE FROM ce_verbose;

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
(20, 'ANY', 'ANY', 'EXACT', 'OutputFormatResolverFactory', 'OUTPUT_FORMAT_RESOLVER_NOT_FOUND', NULL, NULL, 'No output formatter matched.', 'No output format resolver found.', 10, true, CURRENT_TIMESTAMP);
