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
}
