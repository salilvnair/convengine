package com.github.salilvnair.convengine.engine.constants;

public final class ConvEngineInputParamKey {

    private ConvEngineInputParamKey() {
    }

    public static final String MISSING_FIELDS = "missing_fields";
    public static final String MISSING_FIELD_OPTIONS = "missing_field_options";
    public static final String SCHEMA_DESCRIPTION = "schema_description";
    public static final String SCHEMA_FIELD_DETAILS = "schema_field_details";
    public static final String SCHEMA_ID = "schema_id";
    public static final String SCHEMA_JSON = "schema_json";
    public static final String CONTEXT = "context";
    public static final String SESSION = "session";
    public static final String INTENT_SCORES = "intent_scores";
    public static final String INTENT_TOP3 = "intent_top3";
    public static final String INTENT_COLLISION_CANDIDATES = "intent_collision_candidates";
    public static final String FOLLOWUPS = "followups";
    public static final String AGENT_RESOLVER = "agentResolver";

    public static final String POST_INTENT_RULE = "post_intent_rule";
    public static final String RULE_EXECUTION_SOURCE = "rule_execution_source";
    public static final String RULE_EXECUTION_ORIGIN = "rule_execution_origin";
    public static final String RULE_PHASE = "rule_phase";
    public static final String RULE_AGENT_POST_INTENT = "rule_agent_post_intent";
    public static final String RULE_AGENT_POST_MCP = "rule_agent_post_mcp";

    public static final String DIALOGUE_ACT = "dialogue_act";
    public static final String DIALOGUE_ACT_CONFIDENCE = "dialogue_act_confidence";
    public static final String DIALOGUE_ACT_SOURCE = "dialogue_act_source";
    public static final String DIALOGUE_ACT_REGEX = "dialogue_act_regex";
    public static final String DIALOGUE_ACT_REGEX_CONFIDENCE = "dialogue_act_regex_confidence";
    public static final String DIALOGUE_ACT_LLM_CANDIDATE = "dialogue_act_llm_candidate";
    public static final String DIALOGUE_ACT_LLM_CONFIDENCE = "dialogue_act_llm_confidence";
    public static final String DIALOGUE_ACT_LLM_STANDALONE_QUERY = "dialogue_act_llm_standalone_query";
    public static final String DIALOGUE_ACT_GUARD_APPLIED = "dialogue_act_guard_applied";
    public static final String DIALOGUE_ACT_GUARD_REASON = "dialogue_act_guard_reason";
    public static final String STANDALONE_QUERY = "standalone_query";
    public static final String RESOLVED_USER_INPUT = "resolved_user_input";
    public static final String ROUTING_DECISION = "routing_decision";
    public static final String SKIP_SCHEMA_EXTRACTION = "skip_schema_extraction";
    public static final String CORRECTION_APPLIED = "correction_applied";
    public static final String CORRECTION_TARGET_FIELD = "correction_target_field";
    public static final String POLICY_DECISION = "policy_decision";
    public static final String SKIP_INTENT_RESOLUTION = "skip_intent_resolution";
    public static final String PENDING_ACTION_KEY = "pending_action_key";
    public static final String PENDING_ACTION_RESULT = "pending_action_result";
    public static final String PENDING_ACTION_RUNTIME_STATUS = "pending_action_runtime_status";
    public static final String PENDING_ACTION_DISAMBIGUATION_REQUIRED = "pending_action_disambiguation_required";
    public static final String SANITIZED_USER_TEXT = "sanitized_user_text";
    public static final String GUARDRAIL_BLOCKED = "guardrail_blocked";
    public static final String GUARDRAIL_REASON = "guardrail_reason";
    public static final String SKIP_TOOL_EXECUTION = "skip_tool_execution";
    public static final String SKIP_PENDING_ACTION_EXECUTION = "skip_pending_action_execution";
    public static final String TOOL_REQUEST = "tool_request";
    public static final String TOOL_RESULT = "tool_result";
    public static final String TOOL_STATUS = "tool_status";
    public static final String STATE_GRAPH_VALID = "state_graph_valid";
    public static final String STATE_GRAPH_SOFT_BLOCK = "state_graph_soft_block";
    public static final String MEMORY_RECALL = "memory_recall";
    public static final String MEMORY_SESSION_SUMMARY = "memory_session_summary";

    public static final String MCP_ACTION = "mcp_action";
    public static final String MCP_TOOL_CODE = "mcp_tool_code";
    public static final String MCP_TOOL_GROUP = "mcp_tool_group";
    public static final String MCP_TOOL_ARGS = "mcp_tool_args";
    public static final String MCP_OBSERVATIONS = "mcp_observations";
    public static final String MCP_FINAL_ANSWER = "mcp_final_answer";
    public static final String MCP_STATUS = "mcp_status";
    public static final String RULE_TOOL_POST_EXECUTION = "rule_tool_post_execution";
}
