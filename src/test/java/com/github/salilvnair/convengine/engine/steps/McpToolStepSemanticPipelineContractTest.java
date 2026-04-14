package com.github.salilvnair.convengine.engine.steps;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolStepSemanticPipelineContractTest {

    @Test
    void toolStepContainsSemanticPipelineHydrationAndGuardrailHooks() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/salilvnair/convengine/engine/steps/McpToolStep.java"));

        assertTrue(source.contains("enrichSemanticPipelineArgs("));
        assertTrue(source.contains("semanticClarificationQuestionFromObservation("));
        assertTrue(source.contains("SEMANTIC_PIPELINE_SEQUENCE_GUARD_BLOCKED"));
        assertTrue(source.contains("TOOL_DB_SEMANTIC_INTERPRET"));
        assertTrue(source.contains("TOOL_DB_SEMANTIC_QUERY"));
        assertTrue(source.contains("REASON_SEMANTIC_QUERY_AMBIGUITY"));
    }
}
