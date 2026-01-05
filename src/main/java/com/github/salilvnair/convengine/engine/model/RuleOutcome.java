package com.github.salilvnair.convengine.engine.model;

import com.github.salilvnair.convengine.engine.type.RuleAction;

public record RuleOutcome(
        boolean matched,
        RuleAction action,
        String value
) {}
