package com.github.salilvnair.convengine.engine.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPlannerObservationInputContractTest {

    @Test
    void plannerUsesCompactObservationSummariesWithoutLegacyDbkgFields() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/salilvnair/convengine/engine/agent/AgentPlanner.java"));

        assertTrue(source.contains("summarizeObservationForPlanner("));
        assertTrue(source.contains("rowsPreview"));
        assertFalse(source.contains("dbkg_capsule"));
    }
}
