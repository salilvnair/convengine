package com.github.salilvnair.convengine.engine.mcp;

public final class McpConstants {

    private McpConstants() {
    }

    public static final String TOOL_GROUP_DB = "DB";
    public static final String TOOL_GROUP_HTTP_API = "HTTP_API";
    public static final String TOOL_GROUP_WORKFLOW_ACTION = "WORKFLOW_ACTION";
    public static final String TOOL_GROUP_DOCUMENT_RETRIEVAL = "DOCUMENT_RETRIEVAL";
    public static final String TOOL_GROUP_CALCULATOR_TRANSFORM = "CALCULATOR_TRANSFORM";
    public static final String TOOL_GROUP_NOTIFICATION = "NOTIFICATION";
    public static final String TOOL_GROUP_FILES = "FILES";

    public static final String ACTION_ANSWER = "ANSWER";
    public static final String ACTION_CALL_TOOL = "CALL_TOOL";

    public static final String STATUS_SKIPPED_BY_GUARDRAIL = "SKIPPED_BY_GUARDRAIL";
    public static final String STATUS_SKIPPED_DIALOGUE_ACT = "SKIPPED_DIALOGUE_ACT";
    public static final String STATUS_SKIPPED_GREETING = "SKIPPED_GREETING";
    public static final String STATUS_NO_TOOLS_FOR_SCOPE = "NO_TOOLS_FOR_SCOPE";
    public static final String STATUS_ANSWER = "ANSWER";
    public static final String STATUS_FALLBACK = "FALLBACK";
    public static final String STATUS_TOOL_RESULT = "TOOL_RESULT";
    public static final String STATUS_TOOL_ERROR = "TOOL_ERROR";
    public static final String STATUS_GUARDRAIL_BLOCKED = "GUARDRAIL_BLOCKED_NEXT_TOOL";

    public static final String CONTEXT_KEY_MCP = "mcp";
    public static final String CONTEXT_KEY_OBSERVATIONS = "observations";
    public static final String CONTEXT_KEY_FINAL_ANSWER = "finalAnswer";
    public static final String CONTEXT_KEY_LIFECYCLE = "lifecycle";
    public static final String CONTEXT_KEY_TOOL_EXECUTION = "toolExecution";
    public static final String CONTEXT_KEY_STATUS = "status";
    public static final String CONTEXT_KEY_OUTCOME = "outcome";
    public static final String CONTEXT_KEY_FINISHED = "finished";
    public static final String CONTEXT_KEY_BLOCKED = "blocked";
    public static final String CONTEXT_KEY_ERROR = "error";
    public static final String CONTEXT_KEY_ERROR_MESSAGE = "errorMessage";
    public static final String CONTEXT_KEY_PHASE = "phase";
    public static final String CONTEXT_KEY_LAST_ACTION = "lastAction";
    public static final String CONTEXT_KEY_LAST_TOOL_CODE = "lastToolCode";
    public static final String CONTEXT_KEY_LAST_TOOL_GROUP = "lastToolGroup";
    public static final String CONTEXT_KEY_LAST_TOOL_ARGS = "lastToolArgs";
    public static final String CONTEXT_KEY_LAST_TOOL_STATUS = "lastToolStatus";
    public static final String CONTEXT_KEY_TOOL_EXECUTED = "toolExecuted";
    public static final String CONTEXT_KEY_SCOPE_MISMATCH = "scopeMismatch";
    public static final String CONTEXT_KEY_META = "meta";
    public static final String CONTEXT_KEY_RESULT = "result";
    public static final String CONTEXT_KEY_TOOL_CODE = "toolCode";
    public static final String CONTEXT_KEY_TOOL_GROUP = "toolGroup";
    public static final String CONTEXT_OBSERVATION_TOOL_CODE = "toolCode";
    public static final String CONTEXT_OBSERVATION_JSON = "json";
    public static final String FLOW_START = "__START__";

    public static final String OUTCOME_SKIPPED = "SKIPPED";
    public static final String OUTCOME_NO_TOOLS = "NO_TOOLS";
    public static final String OUTCOME_ANSWERED = "ANSWERED";
    public static final String OUTCOME_FALLBACK = "FALLBACK";
    public static final String OUTCOME_BLOCKED = "BLOCKED";
    public static final String OUTCOME_ERROR = "ERROR";
    public static final String OUTCOME_IN_PROGRESS = "IN_PROGRESS";
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_SCOPE_MISMATCH = "SCOPE_MISMATCH";

    public static final String TOOL_STATUS_SUCCESS = "SUCCESS";
    public static final String TOOL_STATUS_ERROR = "ERROR";
    public static final String TOOL_STATUS_SCOPE_MISMATCH = "SKIPPED_SCOPE_MISMATCH";

    public static final String EMPTY_JSON_OBJECT = "{}";
    public static final String NULL_LITERAL = "null";

    public static final String FALLBACK_UNSAFE_NEXT_STEP = "I couldn't decide the next tool step safely.";
    public static final String FALLBACK_TOOL_ERROR = "Tool execution failed safely. Can you narrow the request?";
    public static final String FALLBACK_PLAN_ERROR = "I couldn't plan tool usage safely. Can you rephrase your question?";
    public static final String FALLBACK_GUARDRAIL_BLOCKED = "Tool sequence guardrail blocked this action. Please rephrase or provide missing details.";
    public static final String AUDIT_STAGE_MCP_CONTEXT_CLEARED = "MCP_CONTEXT_CLEARED";
}
