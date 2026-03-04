package com.github.salilvnair.convengine.engine.mcp.knowledge;

import java.util.List;

public final class DbkgConstants {

    private DbkgConstants() {
    }

    public static final String KEY_QUESTION = "question";
    public static final String KEY_TOKENS = "tokens";
    public static final String KEY_RANKED_CASES = "rankedCases";
    public static final String KEY_SELECTED_CASE = "selectedCase";
    public static final String KEY_RANKED_PLAYBOOKS = "rankedPlaybooks";
    public static final String KEY_SELECTED_PLAYBOOK = "selectedPlaybook";
    public static final String KEY_API_FLOWS = "apiFlows";
    public static final String KEY_STEPS = "steps";
    public static final String KEY_TRANSITIONS = "transitions";
    public static final String KEY_STEPS_EXECUTED = "stepsExecuted";
    public static final String KEY_OUTCOME = "outcome";
    public static final String KEY_FINAL_SUMMARY = "finalSummary";
    public static final String KEY_GRAPH_ERROR = "graphError";
    public static final String KEY_DAG_ERROR = "dagError";
    public static final String KEY_CAN_EXECUTE = "canExecute";
    public static final String KEY_NEEDS_CLARIFICATION = "needsClarification";
    public static final String KEY_START_STEP_CODE = "startStepCode";
    public static final String KEY_VALID = "valid";
    public static final String KEY_SUMMARY = "summary";
    public static final String KEY_DBKG_CAPSULE = "dbkgCapsule";
    public static final String KEY_STEP_COUNT = "stepCount";
    public static final String KEY_TRANSITION_COUNT = "transitionCount";
    public static final String KEY_ARGS = "args";
    public static final String KEY_CASE = "case";
    public static final String KEY_PLAYBOOK = "playbook";
    public static final String KEY_STEP_OUTPUTS = "stepOutputs";
    public static final String KEY_PLACEHOLDER_SKIPPED = "placeholderSkipped";
    public static final String KEY_EXECUTION_MODE = "executionMode";
    public static final String KEY_ROW_COUNT = "rowCount";
    public static final String KEY_REQUEST_ROW_COUNT = "requestRowCount";
    public static final String KEY_LAST_ROW_COUNT = "lastRowCount";
    public static final String KEY_LAST_ROWS = "lastRows";
    public static final String KEY_STATUS = "status";
    public static final String KEY_ERROR = "error";
    public static final String KEY_OUTPUT = "output";
    public static final String KEY_HALTED = "halted";
    public static final String KEY_STEP_CODE = "stepCode";
    public static final String KEY_EXECUTOR_CODE = "executorCode";
    public static final String KEY_TEMPLATE_CODE = "templateCode";
    public static final String KEY_QUERY_CODE = "queryCode";
    public static final String KEY_PARAMS = "params";
    public static final String KEY_ROWS = "rows";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_DOMAIN_ENTITIES = "domainEntities";
    public static final String KEY_SYSTEMS = "systems";
    public static final String KEY_DB_OBJECTS = "dbObjects";
    public static final String KEY_DB_COLUMNS = "dbColumns";
    public static final String KEY_JOIN_PATHS = "joinPaths";
    public static final String KEY_STATUS_DICTIONARY = "statusDictionary";
    public static final String KEY_ID_LINEAGE = "idLineage";
    public static final String KEY_PLAYBOOK_STEPS = "playbookSteps";
    public static final String KEY_CONVERSATION_ID = "conversationId";
    public static final String KEY_SESSION = "session";
    public static final String KEY_PLAYBOOK_CODE = "playbookCode";
    public static final String KEY_PLAYBOOK_NAME = "playbookName";
    public static final String KEY_CASE_CODE = "caseCode";
    public static final String KEY_CASE_NAME = "caseName";
    public static final String KEY_FROM_STEP_CODE = "fromStepCode";
    public static final String KEY_TO_STEP_CODE = "toStepCode";
    public static final String KEY_OUTCOME_CODE = "outcomeCode";
    public static final String KEY_CONDITION_EXPR = "conditionExpr";
    public static final String KEY_EXPLANATION = "explanation";
    public static final String KEY_SEVERITY = "severity";
    public static final String KEY_RECOMMENDED_NEXT_ACTION = "recommendedNextAction";
    public static final String KEY_MATCHED_CONDITION = "matchedCondition";
    public static final String KEY_SCORE = "score";
    public static final String KEY_API_FLOW_COUNT = "apiFlowCount";
    public static final String KEY_FIRST_API_FLOW_CODE = "firstApiFlowCode";
    public static final String KEY_FIRST_API_FLOW_NAME = "firstApiFlowName";
    public static final String KEY_FIRST_API_FLOW_SYSTEM_CODE = "firstApiFlowSystemCode";
    public static final String KEY_COMPLETED_STEPS = "completedSteps";
    public static final String KEY_SUMMARY_STYLE = "summaryStyle";
    public static final String KEY_TIME_WINDOW = "timeWindow";
    public static final String KEY_FROM_TS = "fromTs";
    public static final String KEY_HOURS = "hours";
    public static final String KEY_CONFIG_JSON = "configJson";
    public static final String KEY_HALT_ON_ERROR = "haltOnError";
    public static final String KEY_IS_START = "isStart";
    public static final String KEY_LIMIT = "limit";
    public static final String KEY_DIALECT = "dialect";
    public static final String KEY_STEP_CODE_SNAKE = "step_code";
    public static final String KEY_TOOL_CODE = "tool_code";
    public static final String KEY_ERROR_MESSAGE = "error";
    public static final String KEY_SQL = "sql";

    public static final String COL_CASE_CODE = "case_code";
    public static final String COL_PLAYBOOK_CODE = "playbook_code";
    public static final String COL_QUERY_CODE = "query_code";
    public static final String COL_ENABLED = "enabled";
    public static final String COL_SQL_TEMPLATE = "sql_template";
    public static final String COL_DEFAULT_LIMIT = "default_limit";
    public static final String COL_PRIORITY = "priority";
    public static final String COL_CONDITION_EXPR = "condition_expr";
    public static final String COL_OUTCOME_CODE = "outcome_code";
    public static final String COL_SEVERITY = "severity";
    public static final String COL_EXPLANATION_TEMPLATE = "explanation_template";
    public static final String COL_RECOMMENDED_NEXT_ACTION = "recommended_next_action";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_PLACEHOLDER_SKIPPED = "PLACEHOLDER_SKIPPED";

    public static final String EXECUTION_MODE_SEQUENCE_FALLBACK = "SEQUENCE_FALLBACK";
    public static final String EXECUTION_MODE_TRANSITION_DAG = "TRANSITION_DAG";

    public static final String EXECUTOR_QUERY_TEMPLATE = "QUERY_TEMPLATE_EXECUTOR";
    public static final String EXECUTOR_SUMMARY_RENDERER = "SUMMARY_RENDERER";
    public static final String EXECUTOR_TIME_WINDOW_DERIVER = "TIME_WINDOW_DERIVER";
    public static final String STEP_LOOKUP_REQUEST = "LOOKUP_REQUEST";
    public static final String TOOL_CODE_DBKG_INVESTIGATE_EXECUTE = "dbkg.investigate.execute";
    public static final String COMPONENT_DBKG_QUERY_TEMPLATE_STEP_EXECUTOR = "DbkgQueryTemplateStepExecutor";
    public static final String EVENT_DBKG_QUERY_SQL_REFINE_LLM_INPUT = "DBKG_QUERY_SQL_REFINE_LLM_INPUT";
    public static final String EVENT_DBKG_QUERY_SQL_REFINE_LLM_ERROR = "DBKG_QUERY_SQL_REFINE_LLM_ERROR";
    public static final String EVENT_DBKG_QUERY_SQL_REFINE_LLM_OUTPUT = "DBKG_QUERY_SQL_REFINE_LLM_OUTPUT";

    public static final List<String> API_FLOW_TEXT_COLUMNS = List.of(
            "api_name", "description", "system_code", "metadata_json", "llm_hint");
    public static final List<String> API_FLOW_OUTPUT_COLUMNS = List.of(
            "api_code", "api_name", "system_code", "description", "metadata_json", "llm_hint");
    public static final List<String> QUESTION_ARG_KEYS = List.of(
            KEY_QUESTION, "query", "user_input");
    public static final List<String> HINT_TEXT_KEYS = List.of(
            "llm_hint", "description", "purpose", "business_meaning", "explanation_template");

    public static final String MESSAGE_NO_PLAYBOOK_RESOLVED = "No playbook could be resolved from the configured metadata.";
    public static final String MESSAGE_NO_PLAYBOOK_SELECTED = "No playbook could be selected for validation.";
    public static final String MESSAGE_PLAYBOOK_VALID = "Playbook graph is valid and ready for execution.";
    public static final String MESSAGE_PLAYBOOK_INVALID_PREFIX = "Playbook graph is invalid: ";
    public static final String MESSAGE_QUERY_TEMPLATE_NOT_FOUND_PREFIX = "Query template not found: ";
    public static final String MESSAGE_QUERY_TEMPLATE_DISABLED = "Query template is disabled; likely a placeholder awaiting consumer table wiring.";
    public static final String MESSAGE_NO_STEP_EXECUTOR_PREFIX = "No DBKG step executor found for executorCode=";
}
