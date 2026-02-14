package com.github.salilvnair.convengine.engine.pipeline;

import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface EngineStep {
    StepResult execute(EngineSession session);

    enum Name {
        AuditUserInputStep,
        IntentResolutionStep,
        AddContainerDataStep,
        SchemaExtractionStep,
        ResponseResolutionStep,
        AutoAdvanceStep,
        McpToolStep,
        LoadOrCreateConversationStep,
        FallbackIntentStateStep,
        PersistConversationBootstrapStep,
        ResetConversationStep,
        PersistConversationStep,
        PipelineEndGuardStep,
        ResetResolvedIntentStep,
        PolicyEnforcementStep,
        RulesStep,
        Unknown;

        public static Name fromStepName(String stepName) {
            if (stepName == null || stepName.isBlank()) {
                return Unknown;
            }
            for (Name value : values()) {
                if (value.name().equalsIgnoreCase(stepName.trim())) {
                    return value;
                }
            }
            return Unknown;
        }

        public static Name fromStepClass(Class<?> stepClass) {
            if (stepClass == null) {
                return Unknown;
            }
            return fromStepName(stepClass.getSimpleName());
        }

        public boolean matches(String stepName) {
            return name().equalsIgnoreCase(stepName == null ? "" : stepName.trim());
        }
    }
}
