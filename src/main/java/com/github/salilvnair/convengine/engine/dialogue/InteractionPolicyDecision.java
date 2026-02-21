package com.github.salilvnair.convengine.engine.dialogue;

public enum InteractionPolicyDecision {
    EXECUTE_PENDING_ACTION,
    REJECT_PENDING_ACTION,
    FILL_PENDING_SLOT,
    RECLASSIFY_INTENT
}
