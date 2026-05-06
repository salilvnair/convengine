package com.github.salilvnair.convengine.engine.type;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RulePhaseTest {

    @Test
    void normalizeMapsPostResponseAlias() {
        assertEquals(RulePhase.POST_RESPONSE_RESOLUTION.name(), RulePhase.normalize("POST_RESPONSE"));
    }

    @Test
    void normalizeDefaultsToPreResponseResolutionForNull() {
        assertEquals(RulePhase.PRE_RESPONSE_RESOLUTION.name(), RulePhase.normalize(null));
    }
}
