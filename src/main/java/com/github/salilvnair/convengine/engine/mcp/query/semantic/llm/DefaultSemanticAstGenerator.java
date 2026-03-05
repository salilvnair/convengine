package com.github.salilvnair.convengine.engine.mcp.query.semantic.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.AstFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.AstSort;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.SemanticQueryAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.RetrievalResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import com.github.salilvnair.convengine.util.JsonUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class DefaultSemanticAstGenerator implements SemanticAstGenerator {

    private final LlmClient llmClient;
    private final SemanticModelRegistry modelRegistry;
    private final ObjectProvider<List<SemanticAstGenerationInterceptor>> interceptorsProvider;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;
    private final CeConfigResolver configResolver;
    private final PromptTemplateRenderer renderer;
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
                Allowed entities: {{allowed_entities}}
                Join path: {{join_path_json}}
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
        String entity = retrieval.candidateEntities().isEmpty() ? "" : retrieval.candidateEntities().get(0).name();

        String systemPrompt = astSystemPrompt;
        String userPrompt = astUserPrompt;
        String jsonSchema = astSchemaJson;
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("question", question);
        ctx.put("selectedEntity", entity);
        ctx.put("candidateEntities", retrieval.candidateEntities());
        ctx.put("candidateTables", retrieval.candidateTables());
        ctx.put("joinPath", joinPathPlan);
        ctx.put("allowedEntities", model.entities().keySet());

        var selectedEntityModel = model.entities().get(entity);
        jsonSchema = specializeSchemaForEntity(jsonSchema, selectedEntityModel);
        String ctxJson = mapper.writeValueAsString(ctx);
        String selectedEntityDescription = selectedEntityModel == null ? "" : selectedEntityModel.description();
        String selectedEntityFieldsJson = selectedEntityModel == null
                ? "[]"
                : JsonUtil.toJson(selectedEntityModel.fields().keySet());
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
                .selectedEntity(entity == null ? "" : entity)
                .selectedEntityDescription(selectedEntityDescription == null ? "" : selectedEntityDescription)
                .selectedEntityFieldsJson(selectedEntityFieldsJson)
                .allowedEntitiesJson(JsonUtil.toJson(model.entities().keySet()))
                .candidateEntitiesJson(JsonUtil.toJson(retrieval.candidateEntities()))
                .candidateTablesJson(JsonUtil.toJson(retrieval.candidateTables()))
                .joinPathJson(JsonUtil.toJson(joinPathPlan))
                .session(session)
                .extra(session == null ? Map.of() : session.promptTemplateVars())
                .build();
        promptContext.setContext(ctxJson);
        systemPrompt = renderer.render(systemPrompt, promptContext);
        userPrompt = renderer.render(userPrompt, promptContext);

        String llmPrompt = systemPrompt + "\n\n" + userPrompt;
        Map<String, Object> llmInputPayload = new LinkedHashMap<>();
        llmInputPayload.put("systemPrompt", abbreviate(systemPrompt, 800));
        llmInputPayload.put("userPrompt", abbreviate(userPrompt, 1200));
        llmInputPayload.put("hint", abbreviate(llmPrompt, 400));
        llmInputPayload.put("jsonSchemaPreview", abbreviate(jsonSchema, 400));
        llmInputPayload.put("contextPreview", abbreviate(ctxJson, 1200));
        llmInputPayload.put("_meta", meta(question, entity, false, false, false));
        publishLlmEvent(ConvEngineAuditStage.SEMANTIC_AST_LLM_INPUT.name(), session, false, llmInputPayload);
        try {
            String raw = llmClient.generateJsonStrict(session, llmPrompt, jsonSchema, ctxJson);
            SemanticQueryAst ast = mapper.readValue(raw, SemanticQueryAst.class);
            ast = normalizeAstFields(ast, model, entity);
            Map<String, Object> llmOutputPayload = new LinkedHashMap<>();
            llmOutputPayload.put("rawJsonPreview", abbreviate(raw, 1200));
            llmOutputPayload.put("entity", ast == null ? null : ast.entity());
            llmOutputPayload.put("selectCount", ast == null || ast.select() == null ? 0 : ast.select().size());
            llmOutputPayload.put("filterCount", ast == null || ast.filters() == null ? 0 : ast.filters().size());
            llmOutputPayload.put("_meta", meta(question, entity, true, true, true));
            publishLlmEvent(ConvEngineAuditStage.SEMANTIC_AST_LLM_OUTPUT.name(), session, false, llmOutputPayload);
            return new AstGenerationResult(ast, raw, false);
        }
        catch (Exception ex) {
            Map<String, Object> llmErrorPayload = new LinkedHashMap<>();
            llmErrorPayload.put("errorClass", ex.getClass().getName());
            llmErrorPayload.put("errorMessage", ex.getMessage() == null ? "" : ex.getMessage());
            llmErrorPayload.put("_meta", meta(question, entity, true, false, false));
            publishLlmEvent(ConvEngineAuditStage.SEMANTIC_AST_LLM_ERROR.name(), session, true, llmErrorPayload);
            throw ex;
        }
    }

    private SemanticQueryAst normalizeAstFields(SemanticQueryAst ast, SemanticModel model, String selectedEntity) {
        if (ast == null || model == null || model.entities() == null) {
            return ast;
        }
        String targetEntity = ast.entity();
        if (targetEntity == null || targetEntity.isBlank()) {
            targetEntity = selectedEntity;
        }
        SemanticEntity entity = model.entities().get(targetEntity);
        if (entity == null || entity.fields() == null || entity.fields().isEmpty()) {
            return ast;
        }

        Map<String, String> aliasToField = buildFieldAliasMap(entity.fields());

        List<String> normalizedSelect = ast.select() == null ? List.of() : ast.select().stream()
                .map(field -> normalizeField(field, aliasToField, entity.fields()))
                .toList();
        List<AstFilter> normalizedFilters = ast.filters() == null ? List.of() : ast.filters().stream()
                .map(filter -> filter == null ? null : new AstFilter(
                        normalizeField(filter.field(), aliasToField, entity.fields()),
                        filter.op(),
                        filter.value()))
                .toList();
        List<AstSort> normalizedSort = ast.sort() == null ? List.of() : ast.sort().stream()
                .map(sort -> sort == null ? null : new AstSort(
                        normalizeField(sort.field(), aliasToField, entity.fields()),
                        sort.direction()))
                .toList();
        List<String> normalizedGroupBy = ast.groupBy() == null ? List.of() : ast.groupBy().stream()
                .map(field -> normalizeField(field, aliasToField, entity.fields()))
                .toList();

        return new SemanticQueryAst(
                targetEntity,
                normalizedSelect,
                normalizedFilters,
                ast.timeRange(),
                normalizedGroupBy,
                ast.metrics(),
                normalizedSort,
                ast.limit()
        );
    }

    private Map<String, String> buildFieldAliasMap(Map<String, SemanticField> fields) {
        Map<String, String> aliasToField = new LinkedHashMap<>();
        for (Map.Entry<String, SemanticField> e : fields.entrySet()) {
            String fieldName = e.getKey();
            SemanticField field = e.getValue();
            putAlias(aliasToField, normalizeToken(fieldName), fieldName);
            putAlias(aliasToField, normalizeToken(camelToSnake(fieldName)), fieldName);
            if (field != null && field.column() != null) {
                String col = field.column();
                String colName = col.contains(".") ? col.substring(col.lastIndexOf('.') + 1) : col;
                putAlias(aliasToField, normalizeToken(colName), fieldName);
                if (colName.startsWith("zp_")) {
                    putAlias(aliasToField, normalizeToken(colName.substring(3)), fieldName);
                }
            }
        }
        if (fields.containsKey("requestId")) {
            putAlias(aliasToField, "id", "requestId");
        }
        if (fields.containsKey("requestStatus")) {
            putAlias(aliasToField, "status", "requestStatus");
        }
        if (fields.containsKey("requestedAt")) {
            putAlias(aliasToField, "createdat", "requestedAt");
            putAlias(aliasToField, "created", "requestedAt");
        }
        return aliasToField;
    }

    private void putAlias(Map<String, String> aliasToField, String alias, String field) {
        if (alias == null || alias.isBlank() || field == null || field.isBlank()) {
            return;
        }
        if (!aliasToField.containsKey(alias)) {
            aliasToField.put(alias, field);
        }
    }

    private String normalizeField(String rawField, Map<String, String> aliasToField, Map<String, SemanticField> fields) {
        if (rawField == null || rawField.isBlank()) {
            return rawField;
        }
        if (fields.containsKey(rawField)) {
            return rawField;
        }
        String normalized = normalizeToken(rawField);
        String mapped = aliasToField.get(normalized);
        return mapped == null ? rawField : mapped;
    }

    private String normalizeToken(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String camelToSnake(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
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
                    "db.semantic.query",
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
                  "required": ["entity", "select", "filters", "sort", "group_by", "metrics", "limit"],
                  "properties": {
                    "entity": {"type":"string"},
                    "select": {"type":"array","items":{"type":"string"}},
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
                    "sort": {
                      "type":"array",
                      "items":{
                        "type":"object",
                        "additionalProperties":false,
                        "required":["field","direction"],
                        "properties":{
                          "field":{"type":"string"},
                          "direction":{"type":"string","enum":["ASC","DESC"]}
                        }
                      }
                    },
                    "group_by": {"type":"array","items":{"type":"string"}},
                    "metrics": {"type":"array","items":{"type":"string"}},
                    "limit": {"type":"integer"}
                  }
                }
                """;
    }

    private String specializeSchemaForEntity(String baseSchema, SemanticEntity entity) {
        if (baseSchema == null || baseSchema.isBlank() || entity == null || entity.fields() == null || entity.fields().isEmpty()) {
            return baseSchema;
        }
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(baseSchema);
            ObjectNode properties = asObject(root.get("properties"));
            if (properties == null) {
                return baseSchema;
            }
            ArrayNode fieldEnum = mapper.createArrayNode();
            for (String field : entity.fields().keySet()) {
                fieldEnum.add(field);
            }

            // select[].items.enum
            ObjectNode select = asObject(properties.get("select"));
            ObjectNode selectItems = asObject(select == null ? null : select.get("items"));
            if (selectItems != null) {
                selectItems.set("enum", fieldEnum.deepCopy());
            }

            // group_by[].items.enum
            ObjectNode groupBy = asObject(properties.get("group_by"));
            ObjectNode groupByItems = asObject(groupBy == null ? null : groupBy.get("items"));
            if (groupByItems != null) {
                groupByItems.set("enum", fieldEnum.deepCopy());
            }

            // filters[].items.properties.field.enum
            ObjectNode filters = asObject(properties.get("filters"));
            ObjectNode filterItems = asObject(filters == null ? null : filters.get("items"));
            ObjectNode filterProps = asObject(filterItems == null ? null : filterItems.get("properties"));
            ObjectNode filterField = asObject(filterProps == null ? null : filterProps.get("field"));
            if (filterField != null) {
                filterField.set("enum", fieldEnum.deepCopy());
            }

            // sort[].items.properties.field.enum
            ObjectNode sort = asObject(properties.get("sort"));
            ObjectNode sortItems = asObject(sort == null ? null : sort.get("items"));
            ObjectNode sortProps = asObject(sortItems == null ? null : sortItems.get("properties"));
            ObjectNode sortField = asObject(sortProps == null ? null : sortProps.get("field"));
            if (sortField != null) {
                sortField.set("enum", fieldEnum.deepCopy());
            }

            return mapper.writeValueAsString(root);
        } catch (Exception ex) {
            return baseSchema;
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
            return mapper.writeValueAsString(root);
        } catch (Exception ex) {
            // Invalid config JSON should not be "fixed" via string replacement.
            // Fall back to the known-good strict schema.
            return defaultAstJsonSchema();
        }
    }

    private void ensureRootRequired(ObjectNode root) {
        if (root == null) {
            return;
        }
        ArrayNode required = mapper.createArrayNode();
        required.add("entity");
        required.add("select");
        required.add("filters");
        required.add("sort");
        required.add("group_by");
        required.add("metrics");
        required.add("limit");
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
    }

    private void ensureArrayItemsType(ObjectNode properties, String propertyName, String itemType) {
        ObjectNode propertyNode = asObject(properties.get(propertyName));
        if (propertyNode == null) {
            return;
        }
        if (!"array".equals(propertyNode.path("type").asText())) {
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
    }

    private ObjectNode ensureFilterValueNode(ObjectNode root) {
        if (root == null) {
            return null;
        }
        ObjectNode properties = asObject(root.get("properties"));
        if (properties == null) {
            return null;
        }
        ObjectNode filters = asObject(properties.get("filters"));
        if (filters == null) {
            return null;
        }
        ObjectNode items = asObject(filters.get("items"));
        if (items == null) {
            return null;
        }
        ObjectNode itemProperties = asObject(items.get("properties"));
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
}
