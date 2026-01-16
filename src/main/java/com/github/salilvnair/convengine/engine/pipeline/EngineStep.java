package com.github.salilvnair.convengine.engine.pipeline;

import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface EngineStep {
    StepResult execute(EngineSession session);
}
