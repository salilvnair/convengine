package com.github.salilvnair.convengine.engine.pipeline;

import com.github.salilvnair.convengine.engine.model.EngineResult;

public sealed interface StepResult permits StepResult.Continue, StepResult.Stop {

    record Continue() implements StepResult {}
    record Stop(EngineResult result) implements StepResult {}
}
