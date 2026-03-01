package com.github.salilvnair.convengine.engine.constants;

public final class PendingActionConstants {

    private PendingActionConstants() {
    }

    public static final String CONTEXT_PENDING_ACTION = "pending_action";
    public static final String CONTEXT_PENDING_ACTION_CAMEL = "pendingAction";
    public static final String CONTEXT_PENDING_ACTION_KEY = "pending_action_key";
    public static final String CONTEXT_PENDING_ACTION_KEY_CAMEL = "pendingActionKey";
    public static final String CONTEXT_PENDING_ACTION_RUNTIME = "pending_action_runtime";

    public static final String RUNTIME_STATUS = "status";
    public static final String RUNTIME_ACTION_KEY = "action_key";
    public static final String RUNTIME_ACTION_REF = "action_ref";
    public static final String RUNTIME_CREATED_TURN = "created_turn";
    public static final String RUNTIME_CREATED_AT_EPOCH_MS = "created_at_epoch_ms";
    public static final String RUNTIME_EXPIRES_TURN = "expires_turn";
    public static final String RUNTIME_EXPIRES_AT_EPOCH_MS = "expires_at_epoch_ms";
    public static final String RUNTIME_EXPIRED_TURN = "expired_turn";
    public static final String RUNTIME_EXPIRED_AT_EPOCH_MS = "expired_at_epoch_ms";
    public static final String RUNTIME_IN_PROGRESS_TURN = "in_progress_turn";
    public static final String RUNTIME_IN_PROGRESS_AT_EPOCH_MS = "in_progress_at_epoch_ms";
    public static final String RUNTIME_REJECTED_TURN = "rejected_turn";
    public static final String RUNTIME_REJECTED_AT_EPOCH_MS = "rejected_at_epoch_ms";

    public static final String INPUT_PENDING_ACTION_TASK = "pending_action_task";

    public static final String RESULT_FAILED = "FAILED";
}
