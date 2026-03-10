package com.github.salilvnair.convengine.engine.mcp.query.semantic.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstProjection;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstSubqueryFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstSubquerySpec;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstWindowSpec;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.normalize.AstNormalizer;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticIntentRule;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticRelationship;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import com.github.salilvnair.convengine.util.JsonUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Order
@RequiredArgsConstructor
public class DefaultSemanticAstGenerator implements SemanticAstGenerator {

    private static final String TOOL_CODE = "db.semantic.query";

    private final LlmClient llmClient;
    private final SemanticModelRegistry modelRegistry;
    private final ObjectProvider<List<SemanticAstGenerationInterceptor>> interceptorsProvider;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;
    private final CeConfigResolver configResolver;
    private final PromptTemplateRenderer renderer;
    private final AstNormalizer astNormalizer;
    private final ObjectMapper mapper = new ObjectMapper();

    private String astSystemPrompt;
    private String astUserPrompt;
    private String astSchemaJson;

    @PostConstruct
    public void init() {
        this.astSystemPrompt = configResolver.resolveString(
                this,
                "SYSTEM_PROMPT",
                "You are a semantic SQL AST planner. Return JSON only. Do not generate SQL text. Use semantic field keys only."
        );
        this.astUserPrompt = configResolver.resolveString(
                this,
                "USER_PROMPT",
                """
                Question: {{question}}
                Selected entity: {{selected_entity}}
                Selected entity description: {{selected_entity_description}}
                Allowed fields for selected entity: {{selected_entity_fields_json}}
                Allowed values by field (selected entity only): {{selected_entity_allowed_values_json}}
                Relevant metrics: {{relevant_metrics_json}}
                Matched intent rules (max 2): {{matched_intent_rules_json}}
                Relevant value patterns: {{relevant_value_patterns_json}}
                Relevant relationships: {{relevant_relationships_json}}
                Relevant join hints: {{relevant_join_hints_json}}
                Relevant synonyms: {{relevant_synonyms_json}}
                Relevant rules: {{relevant_rules_json}}
                Allowed entities: {{allowed_entities}}
                Candidate entities: {{candidate_entities_json}}
                Candidate tables: {{candidate_tables_json}}
                Join path: {{join_path_json}}
                Guidance:
                - Use ONLY fields from Allowed fields for selected entity.
                - If question field does not belong to selected entity, switch to the correct allowed entity.
                - If a field has allowed_values, only use those values in filters.
                - Do NOT invent field names.
                Context JSON: {{context_json}}
                """
        );
        this.astSchemaJson = normalizeAstSchema(
                configResolver.resolveString(this, "SCHEMA_PROMPT", defaultAstJsonSchema())
        );
    }

    @Override
    public AstGenerationResult generate(String question, RetrievalResult retrieval, JoinPathPlan joinPathPlan, EngineSession session) {
        List<SemanticAstGenerationInterceptor> interceptors = interceptorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(interceptors);
        for (SemanticAstGenerationInterceptor interceptor : interceptors) {
            if (interceptor != null && interceptor.supports(session)) {
                interceptor.beforeGenerate(question, retrieval, joinPathPlan, session);
            }
        }

        try {
            AstGenerationResult result = doGenerate(question, retrieval, joinPathPlan, session);
            AstGenerationResult current = result;
            for (SemanticAstGenerationInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    current = interceptor.afterGenerate(current, session);
                }
            }
            return current;
        } catch (Exception ex) {
            for (SemanticAstGenerationInterceptor interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    interceptor.onError(question, session, ex);
                }
            }
            throw new IllegalStateException("Failed to generate semantic AST: " + ex.getMessage(), ex);
        }
    }

    private AstGenerationResult doGenerate(String question, RetrievalResult retrieval, JoinPathPlan joinPathPlan, EngineSession session) throws Exception {
        SemanticModel model = modelRegistry.getModel();
        String selectedEntity = retrieval == null || retrieval.candidateEntities() == null || retrieval.candidateEntities().isEmpty()
                ? ""
                : retrieval.candidateEntities().getFirst().name();

        GenerationAttempt first = generateAttempt(question, retrieval, joinPathPlan, session, model, selectedEntity, 1, null);
        RepairSuggestion suggestion = inferRepairTarget(first.ast, selectedEntity, model);
        if (suggestion == null || suggestion.targetEntity() == null || suggestion.targetEntity().isBlank()) {
            return new AstGenerationResult(first.ast, first.rawJson, false);
        }

        // one-shot deterministic repair attempt
        Map<String, Object> repairPayload = new LinkedHashMap<>();
        repairPayload.put("sourceEntity", selectedEntity);
        repairPayload.put("targetEntity", suggestion.targetEntity());
        repairPayload.put("unknownFields", suggestion.unknownFields());
        repairPayload.put("reason", "FIELD_OWNERSHIP_REPAIR");
        repairPayload.put("_meta", meta(question, selectedEntity, true, true, true));
        publishLlmEvent("AST_REPAIR_ATTEMPT", session, false, repairPayload);

        try {
            GenerationAttempt repaired = generateAttempt(
                    question,
                    retrieval,
                    joinPathPlan,
                    session,
                    model,
                    suggestion.targetEntity(),
                    2,
                    "Previous AST used unsupported fields " + suggestion.unknownFields() +
                            " for entity " + selectedEntity + ". Use entity " + suggestion.targetEntity() + " and valid fields only."
            );
            return new AstGenerationResult(repaired.ast, repaired.rawJson, true);
        } catch (Exception ex) {
            Map<String, Object> repairErrorPayload = new LinkedHashMap<>();
            repairErrorPayload.put("sourceEntity", selectedEntity);
            repairErrorPayload.put("targetEntity", suggestion.targetEntity());
            repairErrorPayload.put("errorClass", ex.getClass().getName());
            repairErrorPayload.put("errorMessage", ex.getMessage());
            repairErrorPayload.put("_meta", meta(question, selectedEntity, true, true, true));
            publishLlmEvent("AST_REPAIR_ERROR", session, true, repairErrorPayload);
            return new AstGenerationResult(first.ast, first.rawJson, false);
        }
    }

    private GenerationAttempt generateAttempt(String question,
                                              RetrievalResult retrieval,
                                              JoinPathPlan joinPathPlan,
                                              EngineSession session,
                                              SemanticModel model,
                                              String selectedEntity,
                                              int attempt,
                                              String repairHint) throws Exception {
        SemanticEntity selectedEntityModel = model.entities().get(selectedEntity);

        String jsonSchema = specializeSchemaForEntity(astSchemaJson, selectedEntityModel, model);
        String systemPrompt = astSystemPrompt;
        String userPrompt = astUserPrompt;

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("question", question);
        ctx.put("selectedEntity", selectedEntity);
        ctx.put("candidateEntities", retrieval == null ? List.of() : retrieval.candidateEntities());
        ctx.put("candidateTables", retrieval == null ? List.of() : retrieval.candidateTables());
        ctx.put("joinPath", joinPathPlan);
        ctx.put("allowedEntities", model.entities().keySet());
        if (repairHint != null && !repairHint.isBlank()) {
            ctx.put("repairHint", repairHint);
        }

        String ctxJson = mapper.writeValueAsString(ctx);
        String selectedEntityDescription = selectedEntityModel == null ? "" : selectedEntityModel.description();
        String selectedEntityFieldsJson = selectedEntityModel == null
                ? "[]"
                : JsonUtil.toJson(selectedEntityModel.fields().keySet());
        String selectedEntityAllowedValuesJson = selectedEntityModel == null
                ? "{}"
                : JsonUtil.toJson(buildSelectedEntityAllowedValues(selectedEntityModel));
        List<String> entityTables = entityTables(selectedEntityModel);
        String relevantMetricsJson = JsonUtil.toJson(buildRelevantMetrics(question, model, entityTables));
        String matchedIntentRulesJson = JsonUtil.toJson(resolveMatchedIntentRules(question, model, 2));
        String relevantValuePatternsJson = JsonUtil.toJson(buildRelevantValuePatterns(model, selectedEntityModel));
        String relevantRelationshipsJson = JsonUtil.toJson(buildRelevantRelationships(model, entityTables));
        String relevantJoinHintsJson = JsonUtil.toJson(buildRelevantJoinHints(model, entityTables));
        String relevantSynonymsJson = JsonUtil.toJson(buildRelevantSynonyms(question, model));
        String relevantRulesJson = JsonUtil.toJson(buildRelevantRules(model, entityTables));

        Map<String, Object> promptExtra = new LinkedHashMap<>();
        if (session != null && session.promptTemplateVars() != null) {
            promptExtra.putAll(session.promptTemplateVars());
        }
        promptExtra.put("relevant_metrics_json", relevantMetricsJson);
        promptExtra.put("matched_intent_rules_json", matchedIntentRulesJson);
        promptExtra.put("relevant_value_patterns_json", relevantValuePatternsJson);
        promptExtra.put("relevant_relationships_json", relevantRelationshipsJson);
        promptExtra.put("relevant_join_hints_json", relevantJoinHintsJson);
        promptExtra.put("relevant_synonyms_json", relevantSynonymsJson);
        promptExtra.put("relevant_rules_json", relevantRulesJson);

        PromptTemplateContext promptContext = PromptTemplateContext.builder()
                .templateName("SemanticAstGeneration")
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .schemaJson(jsonSchema)
                .context(session == null ? null : session.getContextJson())
                .userInput(session == null ? question : session.getUserText())
                .resolvedUserInput(session == null ? question : session.getResolvedUserInput())
                .standaloneQuery(session == null ? null : session.getStandaloneQuery())
                .conversationHistory(session == null ? null : JsonUtil.toJson(session.conversionHistory()))
                .question(question == null ? "" : question)
                .selectedEntity(selectedEntity == null ? "" : selectedEntity)
                .selectedEntityDescription(selectedEntityDescription == null ? "" : selectedEntityDescription)
                .selectedEntityFieldsJson(selectedEntityFieldsJson)
                .selectedEntityAllowedValuesJson(selectedEntityAllowedValuesJson)
                .allowedEntitiesJson(JsonUtil.toJson(model.entities().keySet()))
                .candidateEntitiesJson(JsonUtil.toJson(retrieval == null ? List.of() : retrieval.candidateEntities()))
                .candidateTablesJson(JsonUtil.toJson(retrieval == null ? List.of() : retrieval.candidateTables()))
                .joinPathJson(JsonUtil.toJson(joinPathPlan))
                .session(session)
                .extra(promptExtra)
                .build();
        promptContext.setContext(ctxJson);

        systemPrompt = renderer.render(systemPrompt, promptContext);
        userPrompt = renderer.render(userPrompt, promptContext);
        if (repairHint != null && !repairHint.isBlank()) {
            userPrompt = userPrompt + "\n\nRepair hint: " + repairHint;
        }

        String llmPrompt = systemPrompt + "\n\n" + userPrompt;
        Map<String, Object> llmInputPayload = new LinkedHashMap<>();
        llmInputPayload.put("attempt", attempt);
        llmInputPayload.put("systemPrompt", abbreviate(systemPrompt, 800));
        llmInputPayload.put("userPrompt", abbreviate(userPrompt, 1400));
        llmInputPayload.put("hint", abbreviate(llmPrompt, 500));
        llmInputPayload.put("jsonSchemaPreview", abbreviate(jsonSchema, 500));
        llmInputPayload.put("contextPreview", abbreviate(ctxJson, 1200));
        llmInputPayload.put("_meta", meta(question, selectedEntity, false, false, false));
        publishLlmEvent(ConvEngineAuditStage.AST_INPUT.name(), session, false, llmInputPayload);

        try {
            String raw = llmClient.generateJsonStrict(session, llmPrompt, jsonSchema, ctxJson);
            SemanticQueryAstV1 ast = mapper.readValue(raw, SemanticQueryAstV1.class);
            ast = astNormalizer.normalize(ast, model, selectedEntity, session);

            Map<String, Object> llmOutputPayload = new LinkedHashMap<>();
            llmOutputPayload.put("attempt", attempt);
            llmOutputPayload.put("rawJsonPreview", abbreviate(raw, 1200));
            llmOutputPayload.put("entity", ast == null ? null : ast.entity());
            llmOutputPayload.put("selectCount", ast == null || ast.select() == null ? 0 : ast.select().size());
            llmOutputPayload.put("filterCount", ast == null || ast.filters() == null ? 0 : ast.filters().size());
            llmOutputPayload.put("_meta", meta(question, selectedEntity, true, true, true));
            publishLlmEvent(ConvEngineAuditStage.AST_OUTPUT.name(), session, false, llmOutputPayload);
            return new GenerationAttempt(ast, raw);
        } catch (Exception ex) {
            Map<String, Object> llmErrorPayload = new LinkedHashMap<>();
            llmErrorPayload.put("attempt", attempt);
            llmErrorPayload.put("errorClass", ex.getClass().getName());
            llmErrorPayload.put("errorMessage", ex.getMessage() == null ? "" : ex.getMessage());
            llmErrorPayload.put("_meta", meta(question, selectedEntity, true, false, false));
            publishLlmEvent(ConvEngineAuditStage.AST_ERROR.name(), session, true, llmErrorPayload);
            throw ex;
        }
    }

    private Map<String, List<String>> buildSelectedEntityAllowedValues(SemanticEntity selectedEntityModel) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (selectedEntityModel == null || selectedEntityModel.fields() == null) {
            return out;
        }
        for (Map.Entry<String, SemanticField> entry : selectedEntityModel.fields().entrySet()) {
            String fieldName = entry.getKey();
            SemanticField field = entry.getValue();
            if (fieldName == null || fieldName.isBlank()) {
                continue;
            }
            if (field != null && field.allowedValues() != null && !field.allowedValues().isEmpty()) {
                out.put(fieldName, field.allowedValues());
            }
        }
        return out;
    }

    private List<String> entityTables(SemanticEntity entity) {
        if (entity == null || entity.tables() == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (entity.tables().primary() != null && !entity.tables().primary().isBlank()) {
            out.add(entity.tables().primary());
        }
        if (entity.tables().related() != null) {
            for (String t : entity.tables().related()) {
                if (t != null && !t.isBlank()) {
                    out.add(t);
                }
            }
        }
        return List.copyOf(out);
    }

    private List<Map<String, Object>> buildRelevantMetrics(String question, SemanticModel model, List<String> entityTables) {
        if (model == null || model.metrics() == null || model.metrics().isEmpty()) {
            return List.of();
        }
        Set<String> tokens = tokenize(question);
        List<Map<String, Object>> out = new ArrayList<>();
        model.metrics().forEach((name, metric) -> {
            if (name == null || name.isBlank() || metric == null) {
                return;
            }
            String desc = metric.description() == null ? "" : metric.description();
            String sql = metric.sql() == null ? "" : metric.sql();
            boolean tableMatch = entityTables.stream().anyMatch(t -> !t.isBlank() && sql.toLowerCase().contains(t.toLowerCase()));
            boolean tokenMatch = tokenize(name + " " + desc).stream().anyMatch(tokens::contains);
            if (tableMatch || tokenMatch) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("description", desc);
                out.add(row);
            }
        });
        if (out.isEmpty()) {
            model.metrics().forEach((name, metric) -> {
                if (out.size() < 3 && name != null && metric != null) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", name);
                    row.put("description", metric.description() == null ? "" : metric.description());
                    out.add(row);
                }
            });
        }
        return out;
    }

    private List<Map<String, Object>> resolveMatchedIntentRules(String question, SemanticModel model, int maxRules) {
        if (question == null || question.isBlank() || model == null || model.intentRules() == null || model.intentRules().isEmpty()) {
            return List.of();
        }
        String q = question.toLowerCase();
        List<Map<String, Object>> matches = new ArrayList<>();
        model.intentRules().forEach((ruleName, rule) -> {
            if (rule == null) {
                return;
            }
            if (!containsAll(q, rule.mustContain())) {
                return;
            }
            int score = matchScore(q, rule.matchAny());
            if (score <= 0) {
                return;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", ruleName);
            row.put("description", rule.description());
            row.put("force_entity", rule.forceEntity());
            row.put("force_mode", rule.forceMode());
            row.put("force_select", rule.forceSelect());
            row.put("_score", score);
            matches.add(row);
        });
        matches.sort((a, b) -> Integer.compare(((Number) b.get("_score")).intValue(), ((Number) a.get("_score")).intValue()));
        List<Map<String, Object>> limited = matches;
        if (matches.size() > maxRules) {
            limited = new ArrayList<>(matches.subList(0, maxRules));
        }
        for (Map<String, Object> row : limited) {
            row.remove("_score");
        }
        return limited;
    }

    private List<Map<String, Object>> buildRelevantValuePatterns(SemanticModel model, SemanticEntity selectedEntity) {
        if (model == null || model.valuePatterns() == null || model.valuePatterns().isEmpty()
                || selectedEntity == null || selectedEntity.fields() == null) {
            return List.of();
        }
        Set<String> fieldNames = selectedEntity.fields().keySet();
        List<Map<String, Object>> out = new ArrayList<>();
        model.valuePatterns().forEach(vp -> {
            if (vp == null) {
                return;
            }
            boolean related = fieldNames.contains(vp.fromField()) || fieldNames.contains(vp.toField());
            if (!related) {
                return;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from_field", vp.fromField());
            row.put("to_field", vp.toField());
            row.put("value_starts_with", vp.valueStartsWith());
            out.add(row);
        });
        return out;
    }

    private List<Map<String, Object>> buildRelevantRelationships(SemanticModel model, List<String> entityTables) {
        if (model == null || model.relationships() == null || model.relationships().isEmpty() || entityTables == null || entityTables.isEmpty()) {
            return List.of();
        }
        Set<String> tableSet = new LinkedHashSet<>(entityTables);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SemanticRelationship rel : model.relationships()) {
            if (rel == null || rel.from() == null || rel.to() == null) {
                continue;
            }
            String fromTable = rel.from().table();
            String toTable = rel.to().table();
            if (!tableSet.contains(fromTable) && !tableSet.contains(toTable)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", rel.name());
            row.put("type", rel.type());
            Map<String, Object> from = new LinkedHashMap<>();
            from.put("table", fromTable);
            from.put("column", rel.from().column());
            Map<String, Object> to = new LinkedHashMap<>();
            to.put("table", toTable);
            to.put("column", rel.to().column());
            row.put("from", from);
            row.put("to", to);
            out.add(row);
        }
        return out;
    }

    private Map<String, List<String>> buildRelevantJoinHints(SemanticModel model, List<String> entityTables) {
        if (model == null || model.joinHints() == null || model.joinHints().isEmpty() || entityTables == null || entityTables.isEmpty()) {
            return Map.of();
        }
        Set<String> tableSet = new LinkedHashSet<>(entityTables);
        Map<String, List<String>> out = new LinkedHashMap<>();
        model.joinHints().forEach((table, hint) -> {
            if (table == null || hint == null || hint.commonlyJoinedWith() == null) {
                return;
            }
            if (!tableSet.contains(table)) {
                return;
            }
            List<String> filtered = hint.commonlyJoinedWith().stream()
                    .filter(t -> t != null && !t.isBlank())
                    .limit(8)
                    .toList();
            if (!filtered.isEmpty()) {
                out.put(table, filtered);
            }
        });
        return out;
    }

    private Map<String, List<String>> buildRelevantSynonyms(String question, SemanticModel model) {
        if (question == null || question.isBlank() || model == null || model.synonyms() == null || model.synonyms().isEmpty()) {
            return Map.of();
        }
        Set<String> tokens = tokenize(question);
        Map<String, List<String>> out = new LinkedHashMap<>();
        model.synonyms().forEach((key, values) -> {
            if (key == null || values == null || values.isEmpty()) {
                return;
            }
            boolean matched = tokens.contains(key.toLowerCase())
                    || values.stream().filter(v -> v != null).map(String::toLowerCase).anyMatch(tokens::contains);
            if (matched) {
                out.put(key, values);
            }
        });
        return out;
    }

    private Map<String, Object> buildRelevantRules(SemanticModel model, List<String> entityTables) {
        if (model == null || model.rules() == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (model.rules().maxResultLimit() != null) {
            out.put("max_result_limit", model.rules().maxResultLimit());
        }
        if (model.rules().denyOperations() != null && !model.rules().denyOperations().isEmpty()) {
            out.put("deny_operations", model.rules().denyOperations());
        }
        if (model.rules().allowedTables() != null && !model.rules().allowedTables().isEmpty()) {
            Set<String> set = entityTables == null ? Set.of() : new LinkedHashSet<>(entityTables);
            List<String> filtered = model.rules().allowedTables().stream()
                    .filter(t -> set.isEmpty() || set.contains(t))
                    .toList();
            out.put("allowed_tables", filtered.isEmpty() ? model.rules().allowedTables().stream().limit(10).toList() : filtered);
        }
        return out;
    }

    private boolean containsAll(String questionLower, List<String> requiredTokens) {
        if (requiredTokens == null || requiredTokens.isEmpty()) {
            return true;
        }
        for (String token : requiredTokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (!questionLower.contains(token.toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    private int matchScore(String questionLower, List<String> matchAny) {
        if (matchAny == null || matchAny.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String token : matchAny) {
            if (token != null && !token.isBlank() && questionLower.contains(token.toLowerCase())) {
                score++;
            }
        }
        return score;
    }

    private Set<String> tokenize(String input) {
        if (input == null || input.isBlank()) {
            return Set.of();
        }
        String[] raw = input.toLowerCase().split("[^a-z0-9_]+");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String token : raw) {
            if (!token.isBlank()) {
                out.add(token);
            }
        }
        return out;
    }

    private RepairSuggestion inferRepairTarget(SemanticQueryAstV1 ast, String selectedEntity, SemanticModel model) {
        if (ast == null || model == null || model.entities() == null || selectedEntity == null || selectedEntity.isBlank()) {
            return null;
        }
        SemanticEntity current = model.entities().get(selectedEntity);
        if (current == null || current.fields() == null || current.fields().isEmpty()) {
            return null;
        }

        Set<String> referenced = collectReferencedFields(ast);
        if (referenced.isEmpty()) {
            return null;
        }

        List<String> unknown = new ArrayList<>();
        for (String field : referenced) {
            if (field == null || field.isBlank()) {
                continue;
            }
            if (current.fields().containsKey(field)) {
                continue;
            }
            if (model.metrics() != null && model.metrics().containsKey(field)) {
                continue;
            }
            unknown.add(field);
        }
        if (unknown.isEmpty()) {
            return null;
        }

        String target = null;
        for (String field : unknown) {
            List<String> owners = new ArrayList<>();
            for (Map.Entry<String, SemanticEntity> entry : model.entities().entrySet()) {
                SemanticEntity entity = entry.getValue();
                if (entity == null || entity.fields() == null) {
                    continue;
                }
                if (entity.fields().containsKey(field)) {
                    owners.add(entry.getKey());
                }
            }
            if (owners.size() != 1) {
                return null;
            }
            String owner = owners.getFirst();
            if (target == null) {
                target = owner;
            } else if (!target.equals(owner)) {
                return null;
            }
        }

        if (target == null || target.equals(selectedEntity)) {
            return null;
        }
        return new RepairSuggestion(target, List.copyOf(unknown));
    }

    private Set<String> collectReferencedFields(SemanticQueryAstV1 ast) {
        Set<String> out = new LinkedHashSet<>();
        if (ast == null) {
            return out;
        }
        if (ast.select() != null) {
            out.addAll(ast.select());
        }
        if (ast.projections() != null) {
            for (AstProjection projection : ast.projections()) {
                if (projection != null && projection.field() != null) {
                    out.add(projection.field());
                }
            }
        }
        if (ast.filters() != null) {
            for (AstFilter filter : ast.filters()) {
                if (filter != null && filter.field() != null) {
                    out.add(filter.field());
                }
            }
        }
        collectGroupFields(ast.where(), out);
        collectGroupFields(ast.having(), out);
        if (ast.sort() != null) {
            for (AstSort sort : ast.sort()) {
                if (sort != null && sort.field() != null) {
                    out.add(sort.field());
                }
            }
        }
        if (ast.groupBy() != null) {
            out.addAll(ast.groupBy());
        }
        if (ast.windows() != null) {
            for (AstWindowSpec window : ast.windows()) {
                if (window == null) {
                    continue;
                }
                if (window.partitionBy() != null) {
                    out.addAll(window.partitionBy());
                }
                if (window.orderBy() != null) {
                    for (AstSort sort : window.orderBy()) {
                        if (sort != null && sort.field() != null) {
                            out.add(sort.field());
                        }
                    }
                }
            }
        }
        if (ast.subqueryFilters() != null) {
            for (AstSubqueryFilter subqueryFilter : ast.subqueryFilters()) {
                if (subqueryFilter == null) {
                    continue;
                }
                if (subqueryFilter.field() != null) {
                    out.add(subqueryFilter.field());
                }
                AstSubquerySpec sub = subqueryFilter.subquery();
                if (sub != null && sub.selectField() != null) {
                    out.add(sub.selectField());
                }
            }
        }
        out.removeIf(v -> v == null || v.isBlank());
        return out;
    }

    private void collectGroupFields(AstFilterGroup group, Set<String> out) {
        if (group == null) {
            return;
        }
        if (group.conditions() != null) {
            for (AstFilter filter : group.conditions()) {
                if (filter != null && filter.field() != null) {
                    out.add(filter.field());
                }
            }
        }
        if (group.groups() != null) {
            for (AstFilterGroup child : group.groups()) {
                collectGroupFields(child, out);
            }
        }
    }

    private void publishLlmEvent(String stage, EngineSession session, boolean error, Map<String, Object> payload) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId != null) {
            auditService.audit(stage, conversationId, payload);
        }
        if (session != null && verbosePublisher != null) {
            verbosePublisher.publish(
                    session,
                    "DefaultSemanticAstGenerator",
                    stage,
                    null,
                    TOOL_CODE,
                    error,
                    payload
            );
        }
    }

    private Map<String, Object> meta(String question,
                                     String selectedEntity,
                                     boolean llmInvoked,
                                     boolean astParsed,
                                     boolean astPrepared) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("stage", "ast-generation");
        meta.put("llmInvoked", llmInvoked);
        meta.put("astParsed", astParsed);
        meta.put("astPrepared", astPrepared);
        meta.put("questionLength", question == null ? 0 : question.length());
        meta.put("selectedEntity", selectedEntity);
        return meta;
    }

    private String abbreviate(String text, int max) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    private String defaultAstJsonSchema() {
        return """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["astVersion", "entity", "select", "projections", "filters", "where", "exists", "subquery_filters", "sort", "group_by", "metrics", "windows", "having", "limit", "offset", "distinct", "join_hints"],
                  "properties": {
                    "astVersion": {"type":"string","enum":["v1"]},
                    "entity": {"type":"string"},
                    "select": {"type":"array","items":{"type":"string"}},
                    "projections": {
                      "type":"array",
                      "items":{
                        "type":"object",
                        "additionalProperties":false,
                        "required":["field","alias"],
                        "properties":{
                          "field":{"type":"string"},
                          "alias":{"type":["string","null"]}
                        }
                      }
                    },
                    "filters": {
                      "type":"array",
                      "items": {
                        "type":"object",
                        "additionalProperties": false,
                        "required": ["field","op","value"],
                        "properties": {
                          "field": {"type":"string"},
                          "op": {"type":"string"},
                          "value": {
                            "type": ["string", "number", "integer", "boolean", "null"]
                          }
                        }
                      }
                    },
                    "where": {"$ref":"#/$defs/filter_group"},
                    "exists":{
                      "type":"array",
                      "items":{
                        "type":"object",
                        "additionalProperties":false,
                        "required":["entity","where","not_exists"],
                        "properties":{
                          "entity":{"type":"string"},
                          "where":{"$ref":"#/$defs/filter_group"},
                          "not_exists":{"type":"boolean"}
                        }
                      }
                    },
                    "subquery_filters":{
                      "type":"array",
                      "items":{
                        "type":"object",
                        "additionalProperties":false,
                        "required":["field","op","subquery"],
                        "properties":{
                          "field":{"type":"string"},
                          "op":{"type":"string"},
                          "subquery":{
                            "type":"object",
                            "additionalProperties":false,
                            "required":["entity","select_field","where","group_by","having","limit"],
                            "properties":{
                              "entity":{"type":"string"},
                              "select_field":{"type":"string"},
                              "where":{"$ref":"#/$defs/filter_group"},
                              "group_by":{"type":"array","items":{"type":"string"}},
                              "having":{"$ref":"#/$defs/filter_group"},
                              "limit":{"type":"integer"}
                            }
                          }
                        }
                      }
                    },
                    "sort": {
                      "type":"array",
                      "items":{
                        "type":"object",
                        "additionalProperties":false,
                        "required":["field","direction","nulls"],
                        "properties":{
                          "field":{"type":"string"},
                          "direction":{"type":"string","enum":["ASC","DESC"]},
                          "nulls":{"type":["string","null"],"enum":["FIRST","LAST",null]}
                        }
                      }
                    },
                    "group_by": {"type":"array","items":{"type":"string"}},
                    "metrics": {"type":"array","items":{"type":"string"}},
                    "windows":{
                      "type":"array",
                      "items":{
                        "type":"object",
                        "additionalProperties":false,
                        "required":["name","function","partition_by","order_by"],
                        "properties":{
                          "name":{"type":["string","null"]},
                          "function":{"type":"string","enum":["ROW_NUMBER"]},
                          "partition_by":{"type":"array","items":{"type":"string"}},
                          "order_by":{"type":"array","items":{
                            "type":"object","additionalProperties":false,"required":["field","direction","nulls"],
                            "properties":{
                              "field":{"type":"string"},
                              "direction":{"type":"string","enum":["ASC","DESC"]},
                              "nulls":{"type":["string","null"],"enum":["FIRST","LAST",null]}
                            }
                          }}
                        }
                      }
                    },
                    "having":{"$ref":"#/$defs/filter_group"},
                    "limit": {"type":"integer"},
                    "offset":{"type":"integer"},
                    "distinct":{"type":"boolean"},
                    "join_hints":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["leftField","rightField","joinType"],"properties":{"leftField":{"type":"string"},"rightField":{"type":"string"},"joinType":{"type":"string"}}}}
                  },
                  "$defs":{
                    "filter_group":{
                      "type":"object",
                      "additionalProperties":false,
                      "required":["op","conditions","groups"],
                      "properties":{
                        "op":{"type":"string","enum":["AND","OR","NOT"]},
                        "conditions":{"type":"array","items":{
                          "type":"object","additionalProperties":false,"required":["field","op","value"],
                          "properties":{"field":{"type":"string"},"op":{"type":"string"},"value":{"type":["string","number","integer","boolean","null","array"],"items":{"type":["string","number","integer","boolean","null"]}}}
                        }},
                        "groups":{"type":"array","items":{"$ref":"#/$defs/filter_group"}}
                      }
                    }
                  }
                }
                """;
    }

    private String specializeSchemaForEntity(String baseSchema, SemanticEntity entity, SemanticModel model) {
        if (baseSchema == null || baseSchema.isBlank()) {
            return baseSchema;
        }
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(baseSchema);
            ObjectNode properties = asObject(root.get("properties"));
            if (properties == null) {
                return baseSchema;
            }

            ArrayNode selectedFieldEnum = mapper.createArrayNode();
            if (entity != null && entity.fields() != null) {
                for (String field : entity.fields().keySet()) {
                    selectedFieldEnum.add(field);
                }
            }

            ArrayNode allFieldEnum = mapper.createArrayNode();
            if (model != null && model.entities() != null) {
                Set<String> all = new LinkedHashSet<>();
                for (SemanticEntity e : model.entities().values()) {
                    if (e != null && e.fields() != null) {
                        all.addAll(e.fields().keySet());
                    }
                }
                for (String f : all) {
                    allFieldEnum.add(f);
                }
            }

            ArrayNode metricEnum = mapper.createArrayNode();
            if (model != null && model.metrics() != null) {
                for (String metric : model.metrics().keySet()) {
                    metricEnum.add(metric);
                }
            }

            ObjectNode selectItems = asObject(asObject(properties.get("select")) == null ? null : asObject(properties.get("select")).get("items"));
            if (selectItems != null && selectedFieldEnum.size() > 0) {
                selectItems.set("enum", selectedFieldEnum.deepCopy());
            }

            ObjectNode projections = asObject(properties.get("projections"));
            ObjectNode projectionItems = asObject(projections == null ? null : projections.get("items"));
            ObjectNode projectionProps = asObject(projectionItems == null ? null : projectionItems.get("properties"));
            ObjectNode projectionField = asObject(projectionProps == null ? null : projectionProps.get("field"));
            if (projectionField != null && selectedFieldEnum.size() > 0) {
                projectionField.set("enum", selectedFieldEnum.deepCopy());
            }

            ObjectNode filters = asObject(properties.get("filters"));
            ObjectNode filterItems = asObject(filters == null ? null : filters.get("items"));
            ObjectNode filterProps = asObject(filterItems == null ? null : filterItems.get("properties"));
            ObjectNode filterField = asObject(filterProps == null ? null : filterProps.get("field"));
            if (filterField != null && selectedFieldEnum.size() > 0) {
                filterField.set("enum", selectedFieldEnum.deepCopy());
            }

            ObjectNode groupByItems = asObject(asObject(properties.get("group_by")) == null ? null : asObject(properties.get("group_by")).get("items"));
            if (groupByItems != null && selectedFieldEnum.size() > 0) {
                groupByItems.set("enum", selectedFieldEnum.deepCopy());
            }

            ObjectNode metricsItems = asObject(asObject(properties.get("metrics")) == null ? null : asObject(properties.get("metrics")).get("items"));
            if (metricsItems != null && metricEnum.size() > 0) {
                metricsItems.set("enum", metricEnum.deepCopy());
            }

            ObjectNode sort = asObject(properties.get("sort"));
            ObjectNode sortItems = asObject(sort == null ? null : sort.get("items"));
            ObjectNode sortProps = asObject(sortItems == null ? null : sortItems.get("properties"));
            ObjectNode sortField = asObject(sortProps == null ? null : sortProps.get("field"));
            if (sortField != null && selectedFieldEnum.size() > 0) {
                sortField.set("enum", selectedFieldEnum.deepCopy());
            }

            ObjectNode windows = asObject(properties.get("windows"));
            ObjectNode windowItems = asObject(windows == null ? null : windows.get("items"));
            ObjectNode windowProps = asObject(windowItems == null ? null : windowItems.get("properties"));
            ObjectNode partitionByItems = asObject(asObject(windowProps == null ? null : windowProps.get("partition_by")) == null ? null : asObject(windowProps.get("partition_by")).get("items"));
            if (partitionByItems != null && selectedFieldEnum.size() > 0) {
                partitionByItems.set("enum", selectedFieldEnum.deepCopy());
            }
            ObjectNode orderBy = asObject(windowProps == null ? null : windowProps.get("order_by"));
            ObjectNode orderByItems = asObject(orderBy == null ? null : orderBy.get("items"));
            ObjectNode orderByProps = asObject(orderByItems == null ? null : orderByItems.get("properties"));
            ObjectNode orderByField = asObject(orderByProps == null ? null : orderByProps.get("field"));
            if (orderByField != null && selectedFieldEnum.size() > 0) {
                orderByField.set("enum", selectedFieldEnum.deepCopy());
            }

            ObjectNode subqueryFilters = asObject(properties.get("subquery_filters"));
            ObjectNode subqueryFilterItems = asObject(subqueryFilters == null ? null : subqueryFilters.get("items"));
            ObjectNode subqueryFilterProps = asObject(subqueryFilterItems == null ? null : subqueryFilterItems.get("properties"));
            ObjectNode subqueryFilterField = asObject(subqueryFilterProps == null ? null : subqueryFilterProps.get("field"));
            if (subqueryFilterField != null && selectedFieldEnum.size() > 0) {
                subqueryFilterField.set("enum", selectedFieldEnum.deepCopy());
            }
            ObjectNode subquerySpec = asObject(subqueryFilterProps == null ? null : subqueryFilterProps.get("subquery"));
            ObjectNode subquerySpecProps = asObject(subquerySpec == null ? null : subquerySpec.get("properties"));
            ObjectNode subquerySelectField = asObject(subquerySpecProps == null ? null : subquerySpecProps.get("select_field"));
            if (subquerySelectField != null && allFieldEnum.size() > 0) {
                subquerySelectField.set("enum", allFieldEnum.deepCopy());
            }

            // make top-level where strict to selected entity fields
            if (selectedFieldEnum.size() > 0) {
                properties.set("where", inlineFilterGroupSchema(selectedFieldEnum.deepCopy()));
            }

            // top-level having allows selected fields + metrics
            ArrayNode havingEnum = selectedFieldEnum.deepCopy();
            if (metricEnum.size() > 0) {
                Set<String> existing = new LinkedHashSet<>();
                for (int i = 0; i < havingEnum.size(); i++) {
                    existing.add(havingEnum.get(i).asText());
                }
                for (int i = 0; i < metricEnum.size(); i++) {
                    String metric = metricEnum.get(i).asText();
                    if (existing.add(metric)) {
                        havingEnum.add(metric);
                    }
                }
            }
            if (havingEnum.size() > 0) {
                properties.set("having", inlineFilterGroupSchema(havingEnum));
            }

            // keep recursive/group/subquery filter groups broad enough for cross-entity subqueries
            ObjectNode defs = asObject(root.get("$defs"));
            ObjectNode filterGroupDef = asObject(defs == null ? null : defs.get("filter_group"));
            if (filterGroupDef != null && allFieldEnum.size() > 0) {
                setFilterGroupFieldEnum(filterGroupDef, allFieldEnum.deepCopy());
            }

            return mapper.writeValueAsString(root);
        } catch (Exception ex) {
            return baseSchema;
        }
    }

    private ObjectNode inlineFilterGroupSchema(ArrayNode fieldEnum) {
        ObjectNode group = mapper.createObjectNode();
        group.put("type", "object");
        group.put("additionalProperties", false);

        ArrayNode required = mapper.createArrayNode();
        required.add("op");
        required.add("conditions");
        required.add("groups");
        group.set("required", required);

        ObjectNode props = mapper.createObjectNode();
        group.set("properties", props);

        ObjectNode op = mapper.createObjectNode();
        op.put("type", "string");
        ArrayNode opEnum = mapper.createArrayNode();
        opEnum.add("AND");
        opEnum.add("OR");
        opEnum.add("NOT");
        op.set("enum", opEnum);
        props.set("op", op);

        ObjectNode conditions = mapper.createObjectNode();
        conditions.put("type", "array");
        ObjectNode items = mapper.createObjectNode();
        items.put("type", "object");
        items.put("additionalProperties", false);
        ArrayNode conditionRequired = mapper.createArrayNode();
        conditionRequired.add("field");
        conditionRequired.add("op");
        conditionRequired.add("value");
        items.set("required", conditionRequired);

        ObjectNode itemProps = mapper.createObjectNode();
        ObjectNode field = mapper.createObjectNode();
        field.put("type", "string");
        field.set("enum", fieldEnum);
        itemProps.set("field", field);

        ObjectNode opField = mapper.createObjectNode();
        opField.put("type", "string");
        itemProps.set("op", opField);

        ObjectNode value = mapper.createObjectNode();
        ArrayNode valueTypes = mapper.createArrayNode();
        valueTypes.add("string");
        valueTypes.add("number");
        valueTypes.add("integer");
        valueTypes.add("boolean");
        valueTypes.add("null");
        valueTypes.add("array");
        value.set("type", valueTypes);
        ObjectNode itemType = mapper.createObjectNode();
        ArrayNode itemTypeValues = mapper.createArrayNode();
        itemTypeValues.add("string");
        itemTypeValues.add("number");
        itemTypeValues.add("integer");
        itemTypeValues.add("boolean");
        itemTypeValues.add("null");
        itemType.set("type", itemTypeValues);
        value.set("items", itemType);
        itemProps.set("value", value);

        items.set("properties", itemProps);
        conditions.set("items", items);
        props.set("conditions", conditions);

        ObjectNode groups = mapper.createObjectNode();
        groups.put("type", "array");
        ObjectNode groupItems = mapper.createObjectNode();
        groupItems.put("$ref", "#/$defs/filter_group");
        groups.set("items", groupItems);
        props.set("groups", groups);

        return group;
    }

    private void setFilterGroupFieldEnum(ObjectNode filterGroupNode, ArrayNode enumValues) {
        ObjectNode properties = asObject(filterGroupNode.get("properties"));
        ObjectNode conditions = asObject(properties == null ? null : properties.get("conditions"));
        ObjectNode items = asObject(conditions == null ? null : conditions.get("items"));
        ObjectNode itemProps = asObject(items == null ? null : items.get("properties"));
        ObjectNode field = asObject(itemProps == null ? null : itemProps.get("field"));
        if (field != null) {
            field.set("enum", enumValues);
        }
    }

    private String normalizeAstSchema(String rawSchema) {
        if (rawSchema == null || rawSchema.isBlank()) {
            return defaultAstJsonSchema();
        }
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(rawSchema);
            ObjectNode valueNode = ensureFilterValueNode(root);
            if (valueNode != null && !valueNode.has("type")) {
                ArrayNode types = mapper.createArrayNode();
                types.add("string");
                types.add("number");
                types.add("integer");
                types.add("boolean");
                types.add("null");
                valueNode.set("type", types);
            }
            ensureRootRequired(root);
            ensureFilterItemRequired(root);
            ensureArrayItems(root);
            ensureStrictRequiredCoverage(root);
            return mapper.writeValueAsString(root);
        } catch (Exception ex) {
            return defaultAstJsonSchema();
        }
    }

    private void ensureRootRequired(ObjectNode root) {
        if (root == null) {
            return;
        }
        ArrayNode required = mapper.createArrayNode();
        required.add("astVersion");
        required.add("entity");
        required.add("select");
        required.add("projections");
        required.add("filters");
        required.add("where");
        required.add("exists");
        required.add("subquery_filters");
        required.add("sort");
        required.add("group_by");
        required.add("metrics");
        required.add("windows");
        required.add("having");
        required.add("limit");
        required.add("offset");
        required.add("distinct");
        required.add("join_hints");
        root.set("required", required);
    }

    private void ensureFilterItemRequired(ObjectNode root) {
        ObjectNode properties = asObject(root == null ? null : root.get("properties"));
        ObjectNode filters = asObject(properties == null ? null : properties.get("filters"));
        ObjectNode items = asObject(filters == null ? null : filters.get("items"));
        if (items == null) {
            return;
        }
        ArrayNode required = mapper.createArrayNode();
        required.add("field");
        required.add("op");
        required.add("value");
        items.set("required", required);
    }

    private void ensureArrayItems(ObjectNode root) {
        ObjectNode properties = asObject(root == null ? null : root.get("properties"));
        if (properties == null) {
            return;
        }
        ensureArrayItemsType(properties, "select", "string");
        ensureSortItemsShape(properties);
        ensureArrayItemsType(properties, "group_by", "string");
        ensureArrayItemsType(properties, "metrics", "string");
        ensureArrayItemsType(properties, "exists", "object");
        ensureArrayItemsType(properties, "subquery_filters", "object");
        ensureArrayItemsType(properties, "windows", "object");
    }

    private void ensureArrayItemsType(ObjectNode properties, String propertyName, String itemType) {
        ObjectNode propertyNode = asObject(properties.get(propertyName));
        if (propertyNode == null || !"array".equals(propertyNode.path("type").asText())) {
            return;
        }
        if (propertyNode.has("items") && propertyNode.get("items").isObject()) {
            return;
        }
        ObjectNode items = mapper.createObjectNode();
        items.put("type", itemType);
        propertyNode.set("items", items);
    }

    private void ensureSortItemsShape(ObjectNode properties) {
        ObjectNode sortNode = asObject(properties.get("sort"));
        if (sortNode == null || !"array".equals(sortNode.path("type").asText())) {
            return;
        }
        ObjectNode items = asObject(sortNode.get("items"));
        if (items == null) {
            items = mapper.createObjectNode();
            sortNode.set("items", items);
        }
        items.put("type", "object");
        items.put("additionalProperties", false);

        ArrayNode required = mapper.createArrayNode();
        required.add("field");
        required.add("direction");
        required.add("nulls");
        items.set("required", required);

        ObjectNode itemProps = asObject(items.get("properties"));
        if (itemProps == null) {
            itemProps = mapper.createObjectNode();
            items.set("properties", itemProps);
        }

        ObjectNode field = asObject(itemProps.get("field"));
        if (field == null) {
            field = mapper.createObjectNode();
            itemProps.set("field", field);
        }
        field.put("type", "string");

        ObjectNode direction = asObject(itemProps.get("direction"));
        if (direction == null) {
            direction = mapper.createObjectNode();
            itemProps.set("direction", direction);
        }
        direction.put("type", "string");
        ArrayNode enumValues = mapper.createArrayNode();
        enumValues.add("ASC");
        enumValues.add("DESC");
        direction.set("enum", enumValues);

        ObjectNode nulls = asObject(itemProps.get("nulls"));
        if (nulls == null) {
            nulls = mapper.createObjectNode();
            itemProps.set("nulls", nulls);
        }
        ArrayNode nullTypes = mapper.createArrayNode();
        nullTypes.add("string");
        nullTypes.add("null");
        nulls.set("type", nullTypes);
        ArrayNode nullEnum = mapper.createArrayNode();
        nullEnum.add("FIRST");
        nullEnum.add("LAST");
        nullEnum.addNull();
        nulls.set("enum", nullEnum);
    }

    private void ensureStrictRequiredCoverage(ObjectNode schemaNode) {
        if (schemaNode == null) {
            return;
        }

        ObjectNode properties = asObject(schemaNode.get("properties"));
        if (properties != null) {
            schemaNode.put("additionalProperties", false);
            ArrayNode required = mapper.createArrayNode();
            properties.fieldNames().forEachRemaining(required::add);
            schemaNode.set("required", required);
            properties.fields().forEachRemaining(entry -> {
                ObjectNode child = asObject(entry.getValue());
                if (child != null) {
                    ensureStrictRequiredCoverage(child);
                }
            });
        }

        ObjectNode items = asObject(schemaNode.get("items"));
        if (items != null) {
            ensureStrictRequiredCoverage(items);
        }

        ObjectNode defs = asObject(schemaNode.get("$defs"));
        if (defs != null) {
            defs.fields().forEachRemaining(entry -> {
                ObjectNode defNode = asObject(entry.getValue());
                if (defNode != null) {
                    ensureStrictRequiredCoverage(defNode);
                }
            });
        }
    }

    private ObjectNode ensureFilterValueNode(ObjectNode root) {
        if (root == null) {
            return null;
        }
        ObjectNode properties = asObject(root.get("properties"));
        ObjectNode filters = asObject(properties == null ? null : properties.get("filters"));
        ObjectNode items = asObject(filters == null ? null : filters.get("items"));
        ObjectNode itemProperties = asObject(items == null ? null : items.get("properties"));
        if (itemProperties == null) {
            return null;
        }
        ObjectNode value = asObject(itemProperties.get("value"));
        if (value != null) {
            return value;
        }
        ObjectNode created = mapper.createObjectNode();
        itemProperties.set("value", created);
        return created;
    }

    private ObjectNode asObject(com.fasterxml.jackson.databind.JsonNode node) {
        return node instanceof ObjectNode objectNode ? objectNode : null;
    }

    private record GenerationAttempt(SemanticQueryAstV1 ast, String rawJson) {}

    private record RepairSuggestion(String targetEntity, List<String> unknownFields) {}
}
