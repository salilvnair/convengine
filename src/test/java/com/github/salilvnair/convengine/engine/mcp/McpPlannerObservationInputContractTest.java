package com.github.salilvnair.convengine.engine.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpPlannerObservationInputContractTest {

    @Test
    void plannerUsesDbkgCapsuleAndCompactObservationSummaries() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/salilvnair/convengine/engine/mcp/McpPlanner.java"));

        assertTrue(source.contains("extraVars.put(\"dbkg_capsule\""));
        assertTrue(source.contains("summarizeObservationForPlanner("));
        assertTrue(source.contains("rowsPreview"));
        assertTrue(source.contains("\"db.semantic.catalog\""));
        assertTrue(source.contains("legacy-dbkg-capsule-v1"));
    }
}
