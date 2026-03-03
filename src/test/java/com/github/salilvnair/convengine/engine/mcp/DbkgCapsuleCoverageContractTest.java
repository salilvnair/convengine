package com.github.salilvnair.convengine.engine.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DbkgCapsuleCoverageContractTest {

    @Test
    void capsuleBuilderReferencesAllDbkgMetadataFamilies() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/github/salilvnair/convengine/engine/mcp/knowledge/DbkgSupportService.java"));

        List<String> requiredTokens = List.of(
                "getCaseTypeTable()", "getCaseSignalTable()",
                "getPlaybookTable()", "getPlaybookSignalTable()",
                "getDomainEntityTable()", "getDomainRelationTable()",
                "getSystemNodeTable()", "getSystemRelationTable()", "getApiFlowTable()",
                "getDbObjectTable()", "getDbColumnTable()", "getDbJoinPathTable()",
                "getStatusDictionaryTable()", "getIdLineageTable()",
                "getQueryTemplateTable()", "getQueryParamRuleTable()",
                "getPlaybookStepTable()", "getPlaybookTransitionTable()", "getOutcomeRuleTable()",
                "getSqlGuardrailTable()", "\"ce_mcp_executor_template\"", "\"ce_mcp_tool\"", "\"ce_mcp_planner\"");

        for (String token : requiredTokens) {
            assertTrue(source.contains(token), "Missing capsule source coverage token: " + token);
        }
    }
}
