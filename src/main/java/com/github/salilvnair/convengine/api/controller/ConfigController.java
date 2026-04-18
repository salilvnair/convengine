package com.github.salilvnair.convengine.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.github.salilvnair.convengine.entity.CeConfig;
import com.github.salilvnair.convengine.entity.CeContainerConfig;
import com.github.salilvnair.convengine.entity.CeIntent;
import com.github.salilvnair.convengine.entity.CeIntentClassifier;
import com.github.salilvnair.convengine.entity.CeMcpDbTool;
import com.github.salilvnair.convengine.entity.CeMcpPlanner;
import com.github.salilvnair.convengine.entity.CeVerbose;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.github.salilvnair.convengine.entity.CeOutputSchema;
import com.github.salilvnair.convengine.entity.CePolicy;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.entity.CeRule;
import com.github.salilvnair.convengine.entity.CeSemanticEntity;
import com.github.salilvnair.convengine.entity.CeSemanticJoinHint;
import com.github.salilvnair.convengine.entity.CeSemanticRelationship;
import com.github.salilvnair.convengine.entity.CeSemanticValuePattern;
import com.github.salilvnair.convengine.repo.CeConfigRepository;
import com.github.salilvnair.convengine.repo.ContainerConfigRepository;
import com.github.salilvnair.convengine.repo.IntentClassifierRepository;
import com.github.salilvnair.convengine.repo.IntentRepository;
import com.github.salilvnair.convengine.repo.McpDbToolRepository;
import com.github.salilvnair.convengine.repo.McpPlannerRepository;
import com.github.salilvnair.convengine.repo.VerboseRepository;
import com.github.salilvnair.convengine.repo.McpToolRepository;
import com.github.salilvnair.convengine.repo.OutputSchemaRepository;
import com.github.salilvnair.convengine.repo.PolicyRepository;
import com.github.salilvnair.convengine.repo.PromptTemplateRepository;
import com.github.salilvnair.convengine.repo.ResponseRepository;
import com.github.salilvnair.convengine.repo.RuleRepository;
import com.github.salilvnair.convengine.repo.SemanticEntityRepository;
import com.github.salilvnair.convengine.repo.SemanticJoinHintRepository;
import com.github.salilvnair.convengine.repo.SemanticRelationshipRepository;
import com.github.salilvnair.convengine.repo.SemanticValuePatternRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

// REST facade used by the ce-builder UI to read/write every ce_* configuration
// table. Mirrors the UI's TABLE_PATHS map in convengine-ui/src/ce-builder/api/ceConfig.api.js.
//
// Endpoints:
//   GET  /api/v1/config/intents                    list all intents (for the picker)
//   GET  /api/v1/config/by-intent/{intentCode}     composite payload of every intent-keyed table
//   POST /api/v1/config/{table-path}/bulk          upsert N rows into one ce_* table
//
// Cache refresh is handled by the existing ConvEngineCacheController at /api/v1/cache/refresh.
@Slf4j
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final IntentRepository intentRepository;
    private final IntentClassifierRepository intentClassifierRepository;
    private final ContainerConfigRepository containerConfigRepository;
    private final CeConfigRepository ceConfigRepository;
    private final McpDbToolRepository mcpDbToolRepository;
    private final VerboseRepository verboseRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final RuleRepository ruleRepository;
    private final ResponseRepository responseRepository;
    private final McpToolRepository mcpToolRepository;
    private final McpPlannerRepository mcpPlannerRepository;
    private final PolicyRepository policyRepository;
    private final OutputSchemaRepository outputSchemaRepository;
    private final SemanticEntityRepository semanticEntityRepository;
    private final SemanticRelationshipRepository semanticRelationshipRepository;
    private final SemanticJoinHintRepository semanticJoinHintRepository;
    private final SemanticValuePatternRepository semanticValuePatternRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // Snake-case mapper so the UI can post the same key casing it uses for
    // canvas subBlocks (intent_code, display_name, ...) and receive rows in the
    // same shape when loading.
    private static final ObjectMapper SNAKE = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .findAndRegisterModules();

    private record TableBinding<T>(Class<T> entity, JpaRepository<T, ?> repo, String intentField) {}

    private Map<String, TableBinding<?>> bindings() {
        Map<String, TableBinding<?>> map = new LinkedHashMap<>();
        map.put("intents", new TableBinding<>(CeIntent.class, intentRepository, "intentCode"));
        map.put("intent-classifiers", new TableBinding<>(CeIntentClassifier.class, intentClassifierRepository, "intentCode"));
        map.put("container-configs", new TableBinding<>(CeContainerConfig.class, containerConfigRepository, "intentCode"));
        map.put("prompt-templates", new TableBinding<>(CePromptTemplate.class, promptTemplateRepository, "intentCode"));
        map.put("rules", new TableBinding<>(CeRule.class, ruleRepository, "intentCode"));
        map.put("responses", new TableBinding<>(CeResponse.class, responseRepository, "intentCode"));
        map.put("mcp-tools", new TableBinding<>(CeMcpTool.class, mcpToolRepository, "intentCode"));
        map.put("mcp-db-tools", new TableBinding<>(CeMcpDbTool.class, mcpDbToolRepository, null));
        map.put("mcp-planners", new TableBinding<>(CeMcpPlanner.class, mcpPlannerRepository, "intentCode"));
        map.put("policies", new TableBinding<>(CePolicy.class, policyRepository, null));
        map.put("configs", new TableBinding<>(CeConfig.class, ceConfigRepository, null));
        map.put("verboses", new TableBinding<>(CeVerbose.class, verboseRepository, "intentCode"));
        map.put("output-schemas", new TableBinding<>(CeOutputSchema.class, outputSchemaRepository, "intentCode"));
        map.put("semantic-entities", new TableBinding<>(CeSemanticEntity.class, semanticEntityRepository, null));
        map.put("semantic-relationships", new TableBinding<>(CeSemanticRelationship.class, semanticRelationshipRepository, null));
        map.put("semantic-join-hints", new TableBinding<>(CeSemanticJoinHint.class, semanticJoinHintRepository, null));
        map.put("semantic-value-patterns", new TableBinding<>(CeSemanticValuePattern.class, semanticValuePatternRepository, null));
        return map;
    }

    @GetMapping("/intents")
    public ResponseEntity<List<Map<String, Object>>> listIntents() {
        List<CeIntent> rows = intentRepository.findAll();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (CeIntent row : rows) {
            out.add(SNAKE.convertValue(row, Map.class));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/by-intent/{intentCode}")
    public ResponseEntity<Map<String, Object>> byIntent(@PathVariable("intentCode") String intentCode) {
        List<Map<String, Object>> groups = new ArrayList<>();

        CeIntent intent = intentRepository.findById(intentCode).orElse(null);
        groups.add(groupOf("intent", intent == null ? List.<CeIntent>of() : List.of(intent)));

        groups.add(groupOf("intent_classifier", queryByIntent(CeIntentClassifier.class, "intentCode", intentCode)));
        groups.add(groupOf("prompt_template", queryByIntent(CePromptTemplate.class, "intentCode", intentCode)));
        groups.add(groupOf("response", queryByIntent(CeResponse.class, "intentCode", intentCode)));
        groups.add(groupOf("rule", queryByIntent(CeRule.class, "intentCode", intentCode)));
        groups.add(groupOf("mcp_tool", queryByIntent(CeMcpTool.class, "intentCode", intentCode)));
        groups.add(groupOf("mcp_planner", queryByIntent(CeMcpPlanner.class, "intentCode", intentCode)));
        groups.add(groupOf("output_schema", queryByIntent(CeOutputSchema.class, "intentCode", intentCode)));
        groups.add(groupOf("container_config", queryByIntent(CeContainerConfig.class, "intentCode", intentCode)));
        groups.add(groupOf("verbose", queryByIntent(CeVerbose.class, "intentCode", intentCode)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent_code", intentCode);
        payload.put("groups", groups);
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/{tablePath}/bulk")
    @Transactional
    public ResponseEntity<Map<String, Object>> bulkUpsert(
            @PathVariable("tablePath") String tablePath,
            @RequestBody Map<String, Object> body
    ) {
        TableBinding<?> binding = bindings().get(tablePath);
        if (binding == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown table path: " + tablePath);
        }
        Object rowsRaw = body == null ? null : body.get("rows");
        if (!(rowsRaw instanceof List<?> rows)) {
            throw new ResponseStatusException(BAD_REQUEST, "Expected { rows: [...] } payload");
        }
        int saved = bulkSave(binding, rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", tablePath);
        out.put("saved", saved);
        return ResponseEntity.ok(out);
    }

    @SuppressWarnings("unchecked")
    private <T> int bulkSave(TableBinding<T> binding, List<?> rows) {
        JpaRepository<T, ?> repo = binding.repo();
        int count = 0;
        for (Object raw : rows) {
            if (!(raw instanceof Map<?, ?> map)) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.startsWith("__")) continue; // drop __clientId / __enabled wrappers
                copy.put(key, e.getValue());
            }
            T entity = SNAKE.convertValue(copy, binding.entity());
            repo.save(entity);
            count++;
        }
        return count;
    }

    private <T> List<T> queryByIntent(Class<T> entity, String property, String intentCode) {
        String jpql = "select e from " + entity.getSimpleName() + " e where e." + property + " = :code";
        return entityManager.createQuery(jpql, entity).setParameter("code", intentCode).getResultList();
    }

    private Map<String, Object> groupOf(String type, List<?> rows) {
        List<Map<String, Object>> serialized = new ArrayList<>(rows.size());
        for (Object row : rows) {
            serialized.add(SNAKE.convertValue(row, Map.class));
        }
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("type", type);
        g.put("rows", serialized);
        return g;
    }
}
