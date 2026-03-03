package com.github.salilvnair.convengine.engine.mcp.knowledge;

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

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_PLACEHOLDER_SKIPPED = "PLACEHOLDER_SKIPPED";

    public static final String EXECUTION_MODE_SEQUENCE_FALLBACK = "SEQUENCE_FALLBACK";
    public static final String EXECUTION_MODE_TRANSITION_DAG = "TRANSITION_DAG";

    public static final String EXECUTOR_QUERY_TEMPLATE = "QUERY_TEMPLATE_EXECUTOR";
    public static final String STEP_LOOKUP_REQUEST = "LOOKUP_REQUEST";

    public static final String MESSAGE_NO_PLAYBOOK_RESOLVED = "No playbook could be resolved from the configured metadata.";
    public static final String MESSAGE_NO_PLAYBOOK_SELECTED = "No playbook could be selected for validation.";
    public static final String MESSAGE_PLAYBOOK_VALID = "Playbook graph is valid and ready for execution.";
    public static final String MESSAGE_PLAYBOOK_INVALID_PREFIX = "Playbook graph is invalid: ";
    public static final String MESSAGE_QUERY_TEMPLATE_NOT_FOUND_PREFIX = "Query template not found: ";
    public static final String MESSAGE_QUERY_TEMPLATE_DISABLED = "Query template is disabled; likely a placeholder awaiting consumer table wiring.";
}
