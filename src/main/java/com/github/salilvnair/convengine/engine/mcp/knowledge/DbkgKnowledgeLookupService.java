package com.github.salilvnair.convengine.engine.mcp.knowledge;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DbkgKnowledgeLookupService {

    private final DbkgSupportService support;
    private final DbkgCaseResolver caseResolver;
    private final DbkgSchemaCatalogService schemaCatalogService;

    public Map<String, Object> lookupKnowledge(Map<String, Object> args, EngineSession session) {
        String question = support.extractQuestion(args, session);
        List<String> tokens = support.normalizeTokens(question);
        List<Map<String, Object>> rankedCases = caseResolver.extractRankedCases(args, session);
        Map<String, Object> selectedCase = rankedCases.isEmpty() ? null : rankedCases.get(0);
        List<Map<String, Object>> rankedPlaybooks = caseResolver.rankPlaybooks(selectedCase, question, tokens);
        Map<String, Object> selectedPlaybook = rankedPlaybooks.isEmpty() ? null : rankedPlaybooks.get(0);

        DbkgPhysicalSchemaCatalog physical = schemaCatalogService.discoverPhysicalSchema();
        List<Map<String, Object>> domainEntities = support.rankTextRows(
                support.readEnabledRows(support.cfg().getDomainEntityTable()),
                tokens,
                List.of("entity_name", "description", "synonyms", "metadata_json", "llm_hint"),
                List.of("entity_code", "entity_name", "description", "metadata_json", "llm_hint"),
                "score");
        List<Map<String, Object>> systems = support.rankTextRows(
                support.readEnabledRows(support.cfg().getSystemNodeTable()),
                tokens,
                List.of("system_name", "description", "metadata_json", "llm_hint"),
                List.of("system_code", "system_name", "system_type", "metadata_json", "llm_hint"),
                "score");
        List<Map<String, Object>> apiFlows = support.rankTextRows(
                support.readEnabledRowsOptional(support.cfg().getApiFlowTable()),
                tokens,
                List.of("api_name", "description", "system_code", "metadata_json", "llm_hint"),
                List.of("api_code", "api_name", "system_code", "description", "metadata_json", "llm_hint"),
                "score");
        List<Map<String, Object>> dbObjects = schemaCatalogService.rankDiscoveredDbObjects(physical, tokens);
        List<Map<String, Object>> dbColumns = schemaCatalogService.rankDiscoveredDbColumns(physical, tokens, dbObjects);
        List<Map<String, Object>> statuses = support.rankTextRows(
                support.readEnabledRows(support.cfg().getStatusDictionaryTable()),
                tokens,
                List.of("dictionary_name", "field_name", "code_label", "business_meaning", "synonyms"),
                List.of("dictionary_name", "field_name", "code_value", "code_label", "business_meaning"),
                "score");
        List<Map<String, Object>> joins = schemaCatalogService.relatedJoinPaths(dbObjects, physical);
        List<Map<String, Object>> lineage = support.rankTextRows(
                support.readEnabledRows(support.cfg().getIdLineageTable()),
                tokens,
                List.of("lineage_code", "source_column_name", "target_column_name", "description"),
                List.of("lineage_code", "source_system_code", "source_object_name", "source_column_name",
                        "target_system_code", "target_object_name", "target_column_name"),
                "score");
        List<Map<String, Object>> steps = selectedPlaybook == null
                ? List.of()
                : support.stepsForPlaybook(String.valueOf(selectedPlaybook.get("playbookCode")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", question);
        response.put("rankedCases", rankedCases);
        response.put("selectedCase", selectedCase);
        response.put("rankedPlaybooks", rankedPlaybooks);
        response.put("selectedPlaybook", selectedPlaybook);
        response.put("domainEntities", domainEntities);
        response.put("systems", systems);
        response.put("apiFlows", apiFlows);
        response.put("dbObjects", dbObjects);
        response.put("dbColumns", dbColumns);
        response.put("joinPaths", joins);
        response.put("statusDictionary", statuses);
        response.put("idLineage", lineage);
        response.put("playbookSteps", steps);
        return response;
    }
}
