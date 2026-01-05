package com.github.salilvnair.convengine.engine.model;

import com.github.salilvnair.convengine.model.OutputPayload;

public record EngineResult(
        String intent,
        String state,
        OutputPayload payload,
        String contextJson
) {}


