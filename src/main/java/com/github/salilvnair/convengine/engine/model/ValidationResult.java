package com.github.salilvnair.convengine.engine.model;


public record ValidationResult(
        String intent,
        String state,
        EngineResult engineResult
) {}
