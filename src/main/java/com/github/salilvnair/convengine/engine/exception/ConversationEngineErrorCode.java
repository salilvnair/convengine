package com.github.salilvnair.convengine.engine.exception;

public enum ConversationEngineErrorCode {

    // =========================
    // Prompt / Template errors
    // =========================
    PROMPT_RENDER_FAILED(
            "Failed to render response prompt",
            true
    ),

    AUDIT_SAVE_FAILED(
            "Failed to save audit record",
            false
    ),

    RESPONSE_MAPPING_NOT_FOUND(
            "No suitable response found for the given state and intent",
            false
    ),

    UNRESOLVED_PROMPT_VARIABLE(
            "Prompt contains unresolved variables",
            false
    ),

    PROMPT_SCHEMA_INVALID(
            "Provided JSON schema is invalid",
            false
    ),

    PROMPT_CONTEXT_MISSING(
            "Required prompt context data is missing",
            false
    ),

    PROMPT_VAR_ACCESS_FAILED(
            "Failed to read @PromptVar field via reflection",
            false
    ),

    // =========================
    // LLM related errors
    // =========================
    LLM_CALL_FAILED(
            "LLM call failed",
            true
    ),

    LLM_TIMEOUT(
            "LLM call timed out",
            true
    ),

    LLM_INVALID_RESPONSE(
            "LLM returned invalid response",
            false
    ),

    LLM_EMPTY_RESPONSE(
            "LLM returned empty response",
            false
    ),

    LLM_SCHEMA_VIOLATION(
            "LLM response does not match required schema",
            false
    ),

    // =========================
    // Intent resolution errors
    // =========================
    INTENT_RESOLUTION_FAILED(
            "Failed to resolve intent",
            false
    ),

    AGENT_INTENT_FAILED(
            "Agent-based intent resolution failed",
            true
    ),

    CLASSIFIER_INTENT_FAILED(
            "Classifier-based intent resolution failed",
            false
    ),

    // =========================
    // Rule engine errors
    // =========================
    RULE_EVALUATION_FAILED(
            "Rule evaluation failed",
            false
    ),

    INVALID_RULE_ACTION(
            "Invalid rule action configured",
            false
    ),

    // =========================
    // Container / Data errors
    // =========================
    CONTAINER_EXECUTION_FAILED(
            "Container execution failed",
            true
    ),

    INVALID_CONTAINER_TRANSFORMER(
            "ContainerDataTransformer bean is invalid",
            false
    ),

    CONTAINER_INTERCEPTOR_FAILED(
            "Container interceptor execution failed",
            false
    ),

    // =========================
    // Engine / pipeline errors
    // =========================
    PIPELINE_STEP_FAILED(
            "Pipeline step execution failed",
            false
    ),

    PIPELINE_CONSTRAINT_VIOLATION(
            "Pipeline ordering constraints violated",
            false
    ),

    PIPELINE_NO_FINAL_RESULT(
            "Pipeline completed without producing final result",
            false
    ),

    PIPELINE_NO_RESPONSE_PAYLOAD(
            "Pipeline ended without response payload",
            false
    ),

    DUPLICATE_ENGINE_STEP(
            "Duplicate EngineStep bean detected",
            false
    ),

    MISSING_BOOTSTRAP_STEP(
            "Missing required ConversationBootstrapStep",
            false
    ),

    MISSING_TERMINAL_STEP(
            "Missing required TerminalStep",
            false
    ),

    MISSING_DEPENDENT_STEP(
            "EngineStep dependency is missing",
            false
    ),

    MISSING_DAG_CYCLE(
            "EngineStep DAG cycle or unsatisfied constraints",
            false
    ),

    // =========================
    // Response resolution errors
    // =========================
    NO_RESPONSE_CONFIGURED(
            "No response configuration found",
            false
    ),

    RESPONSE_RESOLUTION_FAILED(
            "Failed to resolve response",
            false
    ),

    // =========================
    // Persistence / audit errors
    // =========================
    CONVERSATION_PERSIST_FAILED(
            "Failed to persist conversation state",
            true
    ),

    AUDIT_WRITE_FAILED(
            "Failed to write audit entry",
            false
    ),

    // =========================
    // Fallback
    // =========================
    INTERNAL_ERROR(
            "Internal engine error",
            false
    );

    private final String defaultMessage;
    private final boolean recoverable;

    ConversationEngineErrorCode(String defaultMessage, boolean recoverable) {
        this.defaultMessage = defaultMessage;
        this.recoverable = recoverable;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public boolean recoverable() {
        return recoverable;
    }
}
