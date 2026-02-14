package com.github.salilvnair.convengine.engine.hook;

import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface EngineStepHook {

    default boolean supports(EngineStep.Name stepName, EngineSession session) {
        return supports(stepName == null ? null : stepName.name(), session);
    }

    default boolean supports(String stepName, EngineSession session) {
        return true;
    }

    default void beforeStep(EngineStep.Name stepName, EngineSession session) {
        beforeStep(stepName == null ? null : stepName.name(), session);
    }

    default void beforeStep(String stepName, EngineSession session) {
    }

    default void afterStep(EngineStep.Name stepName, EngineSession session, StepResult result) {
        afterStep(stepName == null ? null : stepName.name(), session, result);
    }

    default void afterStep(String stepName, EngineSession session, StepResult result) {
    }

    default void onStepError(EngineStep.Name stepName, EngineSession session, Throwable error) {
        onStepError(stepName == null ? null : stepName.name(), session, error);
    }

    default void onStepError(String stepName, EngineSession session, Throwable error) {
    }
}
