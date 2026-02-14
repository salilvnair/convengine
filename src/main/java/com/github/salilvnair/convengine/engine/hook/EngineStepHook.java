package com.github.salilvnair.convengine.engine.hook;

import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface EngineStepHook {

    default boolean supports(String stepName, EngineSession session) {
        return true;
    }

    default void beforeStep(String stepName, EngineSession session) {
    }

    default void afterStep(String stepName, EngineSession session, StepResult result) {
    }

    default void onStepError(String stepName, EngineSession session, Throwable error) {
    }
}
