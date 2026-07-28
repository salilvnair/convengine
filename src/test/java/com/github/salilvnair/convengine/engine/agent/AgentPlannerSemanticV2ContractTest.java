package com.github.salilvnair.convengine.engine.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPlannerSemanticV2ContractTest {

    @Test
    void defaultPlannerPromptContainsSemanticV2ChainAndClarificationStopRule() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/salilvnair/convengine/engine/agent/AgentPlanner.java"));

        assertTrue(source.contains("`db.semantic.interpret` -> `db.semantic.query` -> `postgres.query`"));
        assertTrue(source.contains("needsClarification=true"));
        assertTrue(source.contains("clarificationQuestion"));
    }
}
