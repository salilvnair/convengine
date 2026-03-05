package com.github.salilvnair.convengine.engine.mcp.query.semantic.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.normalize.AstNormalizer;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultSemanticAstGenerator implements SemanticAstGenerator {

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
        publishLlmEvent(ConvEngineAuditStage.AST_INPUT.name(), session, false, llmInputPayload);
        try {
            String raw = llmClient.generateJsonStrict(session, llmPrompt, jsonSchema, ctxJson);
            SemanticQueryAstV1 ast = mapper.readValue(raw, SemanticQueryAstV1.class);
            ast = astNormalizer.normalize(ast, model, entity, session);
            Map<String, Object> llmOutputPayload = new LinkedHashMap<>();
            llmOutputPayload.put("rawJsonPreview", abbreviate(raw, 1200));
            llmOutputPayload.put("entity", ast == null ? null : ast.entity());
            llmOutputPayload.put("selectCount", ast == null || ast.select() == null ? 0 : ast.select().size());
            llmOutputPayload.put("filterCount", ast == null || ast.filters() == null ? 0 : ast.filters().size());
            llmOutputPayload.put("_meta", meta(question, entity, true, true, true));
            publishLlmEvent(ConvEngineAuditStage.AST_OUTPUT.name(), session, false, llmOutputPayload);
            return new AstGenerationResult(ast, raw, false);
        }
        catch (Exception ex) {
            Map<String, Object> llmErrorPayload = new LinkedHashMap<>();
            llmErrorPayload.put("errorClass", ex.getClass().getName());
            llmErrorPayload.put("errorMessage", ex.getMessage() == null ? "" : ex.getMessage());
            llmErrorPayload.put("_meta", meta(question, entity, true, false, false));
            publishLlmEvent(ConvEngineAuditStage.AST_ERROR.name(), session, true, llmErrorPayload);
            throw ex;
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
                    "where":{
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
                    },
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
                          "nulls":{"type":"string","enum":["FIRST","LAST"]}
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

            ObjectNode projections = asObject(properties.get("projections"));
            ObjectNode projectionItems = asObject(projections == null ? null : projections.get("items"));
            ObjectNode projectionProps = asObject(projectionItems == null ? null : projectionItems.get("properties"));
            ObjectNode projectionField = asObject(projectionProps == null ? null : projectionProps.get("field"));
            if (projectionField != null) {
                projectionField.set("enum", fieldEnum.deepCopy());
            }

            // group_by[].items.enum
            ObjectNode groupBy = asObject(properties.get("group_by"));
            ObjectNode groupByItems = asObject(groupBy == null ? null : groupBy.get("items"));
            if (groupByItems != null) {
                groupByItems.set("enum", fieldEnum.deepCopy());
            }

            // metrics[].items.enum
            if (modelRegistry != null && modelRegistry.getModel() != null && modelRegistry.getModel().metrics() != null) {
                ArrayNode metricEnum = mapper.createArrayNode();
                for (String metric : modelRegistry.getModel().metrics().keySet()) {
                    metricEnum.add(metric);
                }
                ObjectNode metrics = asObject(properties.get("metrics"));
                ObjectNode metricsItems = asObject(metrics == null ? null : metrics.get("items"));
                if (metricsItems != null && metricEnum.size() > 0) {
                    metricsItems.set("enum", metricEnum);
                }
            }

            // filters[].items.properties.field.enum
            ObjectNode filters = asObject(properties.get("filters"));
            ObjectNode filterItems = asObject(filters == null ? null : filters.get("items"));
            ObjectNode filterProps = asObject(filterItems == null ? null : filterItems.get("properties"));
            ObjectNode filterField = asObject(filterProps == null ? null : filterProps.get("field"));
            if (filterField != null) {
                filterField.set("enum", fieldEnum.deepCopy());
            }

            ObjectNode where = asObject(properties.get("where"));
            ObjectNode whereProps = asObject(where == null ? null : where.get("properties"));
            ObjectNode whereConditions = asObject(whereProps == null ? null : whereProps.get("conditions"));
            ObjectNode whereConditionItems = asObject(whereConditions == null ? null : whereConditions.get("items"));
            ObjectNode whereConditionProps = asObject(whereConditionItems == null ? null : whereConditionItems.get("properties"));
            ObjectNode whereField = asObject(whereConditionProps == null ? null : whereConditionProps.get("field"));
            if (whereField != null) {
                whereField.set("enum", fieldEnum.deepCopy());
            }

            ObjectNode subqueryFilters = asObject(properties.get("subquery_filters"));
            ObjectNode subqueryFilterItems = asObject(subqueryFilters == null ? null : subqueryFilters.get("items"));
            ObjectNode subqueryFilterProps = asObject(subqueryFilterItems == null ? null : subqueryFilterItems.get("properties"));
            ObjectNode subqueryFilterField = asObject(subqueryFilterProps == null ? null : subqueryFilterProps.get("field"));
            if (subqueryFilterField != null) {
                subqueryFilterField.set("enum", fieldEnum.deepCopy());
            }

            ObjectNode windows = asObject(properties.get("windows"));
            ObjectNode windowItems = asObject(windows == null ? null : windows.get("items"));
            ObjectNode windowProps = asObject(windowItems == null ? null : windowItems.get("properties"));
            ObjectNode partitionBy = asObject(windowProps == null ? null : windowProps.get("partition_by"));
            ObjectNode partitionByItems = asObject(partitionBy == null ? null : partitionBy.get("items"));
            if (partitionByItems != null) {
                partitionByItems.set("enum", fieldEnum.deepCopy());
            }
            ObjectNode orderBy = asObject(windowProps == null ? null : windowProps.get("order_by"));
            ObjectNode orderByItems = asObject(orderBy == null ? null : orderBy.get("items"));
            ObjectNode orderByProps = asObject(orderByItems == null ? null : orderByItems.get("properties"));
            ObjectNode orderByField = asObject(orderByProps == null ? null : orderByProps.get("field"));
            if (orderByField != null) {
                orderByField.set("enum", fieldEnum.deepCopy());
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
            ensureStrictRequiredCoverage(root);
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

    // OpenAI strict json_schema requires "required" to include every property key on object schemas.
    // We enforce it recursively so config mistakes don't break runtime.
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
