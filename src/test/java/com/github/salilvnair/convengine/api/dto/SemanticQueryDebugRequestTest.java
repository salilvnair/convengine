package com.github.salilvnair.convengine.api.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticQueryDebugRequestTest {

    @Test
    void defaultsToEnabledWhenFlagsAreNull() {
        SemanticQueryDebugRequest request = new SemanticQueryDebugRequest();

        assertTrue(request.includeRetrieval());
        assertTrue(request.includeJsonPath());
        assertTrue(request.includeAst());
        assertTrue(request.includeSqlGeneration());
        assertTrue(request.includeSqlExecution());
    }

    @Test
    void respectsExplicitFlagValues() {
        SemanticQueryDebugRequest request = new SemanticQueryDebugRequest();
        request.setIncludeRetrieval(false);
        request.setIncludeJsonPath(false);
        request.setIncludeAst(false);
        request.setIncludeSqlGeneration(false);
        request.setIncludeSqlExecution(false);

        assertFalse(request.includeRetrieval());
        assertFalse(request.includeJsonPath());
        assertFalse(request.includeAst());
        assertFalse(request.includeSqlGeneration());
        assertFalse(request.includeSqlExecution());
    }
}
