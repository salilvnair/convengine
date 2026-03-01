package com.github.salilvnair.convengine.engine.constants;

public final class CorrectionConstants {

    private CorrectionConstants() {
    }

    public static final String STEP_NAME = "CorrectionStep";
    public static final String AUDIT_STAGE_PREFIX = "CORRECTION_STEP_";

    public static final String EVENT_CONFIRM_ACCEPT = "CONFIRM_ACCEPT";
    public static final String EVENT_CORRECTION_PATCH_APPLIED = "CORRECTION_PATCH_APPLIED";
    public static final String EVENT_RETRY_REQUESTED = "RETRY_REQUESTED";
}
