package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.mcp.knowledge.DbkgSupportService;
import com.github.salilvnair.convengine.engine.mcp.model.McpPlan;
import com.github.salilvnair.convengine.engine.mcp.model.McpObservation;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpPlanner;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
public class McpPlanner {
    private static final Pattern TOOL_CODE_IN_BACKTICKS = Pattern.compile("`([A-Za-z0-9_.-]+)`");

    private static final String DEFAULT_DB_SYSTEM_PROMPT = """

                You are an MCP planning agent inside ConvEngine.

                You will receive:
                - user_input
                - contextJson (may contain prior tool observations)
                - available tools (DB-driven list)

                Your job:
                1) Decide the next step:
                   - CALL_TOOL (choose a tool_code and args)
                   - ANSWER (when enough observations exist)
                2) Be conservative and safe.
                3) Prefer getting schema first if schema is missing AND the question needs DB knowledge.

                Rules:
                - Never invent tables/columns. If unknown, call postgres.schema first.
                - For postgres.query, choose identifiers only if schema observation confirms them.
                - Keep args minimal.
                - If user question is ambiguous, return ANSWER with an answer that asks ONE clarifying question.

                Return JSON ONLY.

            """;

    private static final String DEFAULT_DB_USER_PROMPT = """

            User input:
            {{user_input}}

            Context JSON:
            {{context}}

            Available MCP tools:
            {{mcp_tools}}

            Existing MCP observations (if any):
            {{mcp_observations}}

            Return JSON EXACTLY in this schema:
            {
              "action": "CALL_TOOL" | "ANSWER",
              "tool_code": "<tool_code_or_null>",
              "args": { },
              "answer": "<text_or_null>"
            }

            """;

    private final StaticConfigurationCacheService staticCacheService;
    private final PromptTemplateRenderer renderer;
    private final LlmClient llm;
    private final AuditService audit;
    private final CeConfigResolver configResolver;
    private final VerboseMessagePublisher verbosePublisher;
    private final ConvEngineMcpConfig mcpConfig;
    private final ObjectProvider<DbkgSupportService> dbkgSupportServiceProvider;

    private final ObjectMapper mapper = new ObjectMapper();

    public McpPlan plan(EngineSession session, List<CeMcpTool> tools, List<McpObservation> observations) {
        PlannerPromptSet promptSet = resolvePlannerPromptSet(session);
        List<CeMcpTool> plannerTools = filterToolsByPlannerPrompt(tools, promptSet);

        String toolsJson = JsonUtil.toJson(
                plannerTools.stream().map(t -> new ToolView(t.getToolCode(), t.getToolGroup(), t.getDescription())).toList());

        ObservationPayload obsPayload = buildObservationsPayload(observations);
        String obsJson = obsPayload.json();
        String dbkgCapsuleJson = resolveDbkgCapsuleJson(session, obsPayload.dbkgCapsuleJson());

        Map<String, Object> extraVars = new LinkedHashMap<>(session.promptTemplateVars());
        extraVars.remove("mcp_observations");
        extraVars.remove("MCP_OBSERVATIONS");
        extraVars.put("dbkg_capsule", dbkgCapsuleJson);
        extraVars.put("DBKG_CAPSULE", dbkgCapsuleJson);
        PlannerTimeContext timeContext = resolvePlannerTimeContext();
        PromptTemplateContext ctx = PromptTemplateContext.builder()
                .templateName("McpPlanner")
                .systemPrompt(promptSet.systemPrompt())
                .userPrompt(promptSet.userPrompt())
                .context(session.getContextJson())
                .userInput(session.getUserText())
                .resolvedUserInput(session.getResolvedUserInput())
                .standaloneQuery(session.getStandaloneQuery())
                .conversationHistory(JsonUtil.toJson(session.conversionHistory()))
                .mcpTools(toolsJson)
                .mcpObservations(obsJson)
                .currentDate(timeContext.currentDate())
                .currentDateTime(timeContext.currentDateTime())
                .currentYear(timeContext.currentYear())
                .currentTimezone(timeContext.currentTimezone())
                .currentSystemDateTime(timeContext.currentSystemDateTime())
                .currentSystemTimezone(timeContext.currentSystemTimezone())
                .extra(extraVars)
                .session(session)
                .build();

        String systemPrompt = renderer.render(promptSet.systemPrompt(), ctx);
        String userPrompt = renderer.render(promptSet.userPrompt(), ctx);

        String schema = """
                {
                  "type":"object",
                  "required":["action","tool_code","args","answer"],
                  "properties":{
                    "action":{"type":"string"},
                    "tool_code":{"type":["string","null"]},
                    "args":{"type":"object"},
                    "answer":{"type":["string","null"]}
                  },
                  "additionalProperties":false
                }
                """;

        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put(ConvEnginePayloadKey.TEMPLATE_FROM_CE_CONFIG_MCP_PLANNER, promptSet.source());
        inputPayload.put(ConvEnginePayloadKey.SYSTEM_PROMPT, systemPrompt);
        inputPayload.put(ConvEnginePayloadKey.USER_PROMPT, userPrompt);
        inputPayload.put(ConvEnginePayloadKey.SCHEMA, schema);
        inputPayload.put("mcp_observations_compacted", obsPayload.compacted());
        inputPayload.put("mcp_observations_raw_chars", obsPayload.rawChars());
        inputPayload.put("mcp_observations_final_chars", obsPayload.finalChars());
        inputPayload.put("dbkg_capsule_chars", dbkgCapsuleJson == null ? 0 : dbkgCapsuleJson.length());
        audit.audit(ConvEngineAuditStage.MCP_PLAN_LLM_INPUT, session.getConversationId(), inputPayload);
        verbosePublisher.publish(session, "McpPlanner", "MCP_PLAN_LLM_INPUT", null, null, false, inputPayload);

        LlmInvocationContext.set(
                session.getConversationId(),
                session.getIntent(),
                session.getState());

        String out;
        try {
            out = llm.generateJson(session, systemPrompt + "\n\n" + userPrompt, schema, session.getContextJson());
        } catch (Exception e) {
            verbosePublisher.publish(session, "McpPlanner", "MCP_PLAN_LLM_ERROR", null, null, true,
                    Map.of("error", String.valueOf(e.getMessage())));
            throw e;
        }

        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put(ConvEnginePayloadKey.JSON, out);
        audit.audit(ConvEngineAuditStage.MCP_PLAN_LLM_OUTPUT, session.getConversationId(), outputPayload);
        verbosePublisher.publish(session, "McpPlanner", "MCP_PLAN_LLM_OUTPUT", null, null, false, outputPayload);

        try {
            McpPlan parsed = mapper.readValue(out, McpPlan.class);
            return normalizePlan(parsed);
        } catch (Exception e) {
            return new McpPlan(McpConstants.ACTION_ANSWER, null, java.util.Map.of(),
                    McpConstants.FALLBACK_PLAN_ERROR);
        }
    }

    private record ToolView(String tool_code, String tool_group, String description) {
    }

    private List<CeMcpTool> filterToolsByPlannerPrompt(List<CeMcpTool> tools, PlannerPromptSet promptSet) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        Set<String> mentionedToolCodes = extractMentionedToolCodes(
                promptSet == null ? null : promptSet.systemPrompt(),
                promptSet == null ? null : promptSet.userPrompt());
        return filterToolsByMentionedCodes(tools, mentionedToolCodes);
    }

    static List<CeMcpTool> filterToolsByMentionedCodes(List<CeMcpTool> tools, Set<String> mentionedToolCodes) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        if (mentionedToolCodes.isEmpty()) {
            return tools;
        }
        List<CeMcpTool> filtered = tools.stream()
                .filter(t -> t.getToolCode() != null && mentionedToolCodes.contains(t.getToolCode().trim().toLowerCase(Locale.ROOT)))
                .toList();
        return filtered.isEmpty() ? tools : filtered;
    }

    static Set<String> extractMentionedToolCodes(String... prompts) {
        Set<String> codes = new HashSet<>();
        if (prompts == null) {
            return codes;
        }
        for (String prompt : prompts) {
            collectToolCodes(prompt, codes);
        }
        return codes;
    }

    private static void collectToolCodes(String prompt, Set<String> out) {
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        Matcher matcher = TOOL_CODE_IN_BACKTICKS.matcher(prompt);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value != null && !value.isBlank() && value.contains(".")) {
                out.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    private McpPlan normalizePlan(McpPlan plan) {
        if (plan == null) {
            return new McpPlan(McpConstants.ACTION_ANSWER, null, Map.of(), McpConstants.FALLBACK_PLAN_ERROR);
        }

        String toolCode = trimToNull(plan.tool_code());
        String answer = trimToNull(plan.answer());
        Map<String, Object> args = plan.args() == null ? Map.of() : new LinkedHashMap<>(plan.args());
        String action = normalizeAction(plan.action(), toolCode, answer);

        if (McpConstants.ACTION_CALL_TOOL.equals(action)) {
            if (toolCode == null) {
                return new McpPlan(McpConstants.ACTION_ANSWER, null, Map.of(), McpConstants.FALLBACK_UNSAFE_NEXT_STEP);
            }
        } else {
            args = Map.of();
        }

        return new McpPlan(action, toolCode, args, answer);
    }

    private String normalizeAction(String rawAction, String toolCode, String answer) {
        String action = trimToNull(rawAction);
        if (action != null) {
            String upper = action.toUpperCase(Locale.ROOT);
            if (McpConstants.ACTION_CALL_TOOL.equals(upper)) {
                return McpConstants.ACTION_CALL_TOOL;
            }
            if (McpConstants.ACTION_ANSWER.equals(upper)) {
                return McpConstants.ACTION_ANSWER;
            }
        }
        if (toolCode != null) {
            return McpConstants.ACTION_CALL_TOOL;
        }
        if (answer != null) {
            return McpConstants.ACTION_ANSWER;
        }
        return McpConstants.ACTION_ANSWER;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private PlannerPromptSet resolvePlannerPromptSet(EngineSession session) {
        String intent = session == null ? null : session.getIntent();
        String state = session == null ? null : session.getState();

        return staticCacheService.findFirstMcpPlanner(intent, state)
                .map(this::fromTablePlanner)
                .orElseGet(this::fromLegacyConfig);
    }

    private PlannerPromptSet fromTablePlanner(CeMcpPlanner planner) {
        String source = "ce_mcp_planner(planner_id="
                + planner.getPlannerId()
                + ", intent_code="
                + planner.getIntentCode()
                + ", state_code="
                + planner.getStateCode()
                + ")";
        return new PlannerPromptSet(planner.getSystemPrompt(), planner.getUserPrompt(), source);
    }

    private PlannerPromptSet fromLegacyConfig() {
        String system = resolveLegacyPrompt("DB_SYSTEM_PROMPT", "SYSTEM_PROMPT", DEFAULT_DB_SYSTEM_PROMPT);
        String user = resolveLegacyPrompt("DB_USER_PROMPT", "USER_PROMPT", DEFAULT_DB_USER_PROMPT);
        return new PlannerPromptSet(system, user,
                "ce_config(DB_USER_PROMPT,DB_SYSTEM_PROMPT -> USER_PROMPT,SYSTEM_PROMPT fallback)");
    }

    private ObservationPayload buildObservationsPayload(List<McpObservation> observations) {
        int maxKeep = configResolver.resolveInt(this, "MCP_PLANNER_MAX_OBSERVATIONS_COUNT", 2);
        List<McpObservation> recentObservations = observations;
        if (observations.size() > maxKeep && maxKeep > 0) {
            recentObservations = observations.subList(observations.size() - maxKeep, observations.size());
        }

        String raw = JsonUtil.toJson(recentObservations);
        int maxChars = resolvePlannerMaxObservationChars();

        // If raw is small, we STILL want to unpack strings that contain nested JSON
        // so the LLM doesn't see double-escaped strings
        List<Map<String, Object>> compact = new ArrayList<>();
        Set<String> seenEntries = new HashSet<>();
        String latestDbkgCapsuleJson = "{}";
        for (McpObservation observation : recentObservations) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("toolCode", observation.toolCode());

            JsonNode parsed = parseJson(observation.json());
            String observedCapsule = extractDbkgCapsuleJson(parsed, observation.toolCode());
            if (observedCapsule != null && !observedCapsule.isBlank() && !"{}".equals(observedCapsule)) {
                latestDbkgCapsuleJson = observedCapsule;
            }
            JsonNode sanitized = stripPlannerRedundantPayload(parsed);
            String sanitizedRaw = JsonUtil.toJson(sanitized);
            entry.put("json", summarizeObservationForPlanner(sanitized, sanitizedRaw, maxChars));

            String signature = JsonUtil.toJson(entry);
            if (seenEntries.add(signature)) {
                compact.add(entry);
            }
        }

        String compactJson = JsonUtil.toJson(compact);
        return new ObservationPayload(compactJson, raw.length() > maxChars, raw.length(), compactJson.length(), latestDbkgCapsuleJson);
    }

    private JsonNode stripPlannerRedundantPayload(JsonNode parsed) {
        if (parsed == null || parsed.isNull() || !parsed.isObject()) {
            return parsed;
        }
        if (!mcpConfig.getDb().semanticCatalogConfig().isKnowledgeCapsule()) {
            return parsed;
        }
        JsonNode clone = parsed.deepCopy();
        if (clone instanceof com.fasterxml.jackson.databind.node.ObjectNode objectNode) {
            objectNode.remove("dbkgCapsule");
        }
        return clone;
    }

    private String extractDbkgCapsuleJson(JsonNode parsed, String toolCode) {
        if (!mcpConfig.getDb().semanticCatalogConfig().isKnowledgeCapsule()) {
            return "{}";
        }
        if (parsed == null || !parsed.isObject()) {
            return "{}";
        }
        JsonNode capsule = parsed.get("dbkgCapsule");
        if (capsule != null && !capsule.isNull()) {
            return capsule.toString();
        }
        if (toolCode != null && "db.semantic.catalog".equalsIgnoreCase(toolCode.trim())) {
            return JsonUtil.toJson(buildLegacyDbkgCapsuleFromKnowledge(parsed));
        }
        return "{}";
    }

    private Map<String, Object> buildLegacyDbkgCapsuleFromKnowledge(JsonNode parsed) {
        Map<String, Object> capsule = new LinkedHashMap<>();
        capsule.put("version", "legacy-dbkg-capsule-v1");
        capsule.put("source", "db.semantic.catalog");

        JsonNode queryKnowledge = parsed.path("queryKnowledge");
        JsonNode schemaKnowledge = parsed.path("schemaKnowledge");
        JsonNode insights = parsed.path("insights");

        Map<String, Object> sourceCoverage = new LinkedHashMap<>();
        sourceCoverage.put("ce_mcp_query_knowledge", queryKnowledge.isArray() ? queryKnowledge.size() : 0);
        sourceCoverage.put("ce_mcp_schema_knowledge", schemaKnowledge.isArray() ? schemaKnowledge.size() : 0);
        capsule.put("sourceCoverage", sourceCoverage);

        Map<String, List<String>> columnsByObject = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> validValuesByObject = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> schemaKnowledgeByObject = new LinkedHashMap<>();
        List<String> objects = new ArrayList<>();
        if (schemaKnowledge.isArray()) {
            for (JsonNode row : schemaKnowledge) {
                String tableName = text(row, "tableName");
                String columnName = text(row, "columnName");
                String description = text(row, "description");
                String tags = text(row, "tags");
                String validValues = text(row, "validValues");
                if (!tableName.isBlank() && !objects.contains(tableName)) {
                    objects.add(tableName);
                }
                if (!tableName.isBlank() && !columnName.isBlank()) {
                    List<String> cols = columnsByObject.computeIfAbsent(tableName, ignored -> new ArrayList<>());
                    if (!cols.contains(columnName) && cols.size() < 20) {
                        cols.add(columnName);
                    }
                    if (!validValues.isBlank()) {
                        Map<String, List<String>> tableValues = validValuesByObject.computeIfAbsent(tableName, ignored -> new LinkedHashMap<>());
                        tableValues.put(columnName, splitCsvLike(validValues));
                    }
                    List<Map<String, Object>> rows = schemaKnowledgeByObject.computeIfAbsent(tableName, ignored -> new ArrayList<>());
                    Map<String, Object> columnInfo = new LinkedHashMap<>();
                    columnInfo.put("columnName", columnName);
                    if (!description.isBlank()) {
                        columnInfo.put("description", description);
                    }
                    if (!tags.isBlank()) {
                        columnInfo.put("tags", splitCsvLike(tags));
                    }
                    if (!validValues.isBlank()) {
                        columnInfo.put("validValues", splitCsvLike(validValues));
                    }
                    rows.add(columnInfo);
                }
                if (objects.size() >= 20 && columnsByObject.size() >= 20) {
                    break;
                }
            }
        }

        List<Map<String, Object>> queryTemplates = new ArrayList<>();
        if (queryKnowledge.isArray()) {
            for (JsonNode row : queryKnowledge) {
                Map<String, Object> template = new LinkedHashMap<>();
                String queryText = text(row, "queryText");
                String description = text(row, "description");
                String preparedSql = text(row, "preparedSql");
                String tags = text(row, "tags");
                String apiHints = text(row, "apiHints");
                if (!queryText.isBlank()) {
                    template.put("queryText", compactJsonValue(queryText, 200));
                }
                if (!description.isBlank()) {
                    template.put("purpose", compactJsonValue(description, 300));
                }
                if (!preparedSql.isBlank()) {
                    template.put("preparedSqlPreview", compactJsonValue(preparedSql, 400));
                }
                if (!tags.isBlank()) {
                    template.put("tags", splitCsvLike(tags));
                }
                if (!apiHints.isBlank()) {
                    template.put("apiHints", splitCsvLike(apiHints));
                }
                if (!template.isEmpty()) {
                    queryTemplates.add(template);
                }
                if (queryTemplates.size() >= 20) {
                    break;
                }
            }
        }

        Map<String, Object> sqlGraph = new LinkedHashMap<>();
        sqlGraph.put("objects", objects);
        sqlGraph.put("columnsByObject", columnsByObject);
        sqlGraph.put("validValuesByObject", validValuesByObject);
        sqlGraph.put("schemaKnowledgeByObject", schemaKnowledgeByObject);
        sqlGraph.put("queryTemplates", queryTemplates);
        sqlGraph.put("joinPaths", List.of());
        sqlGraph.put("statusDictionary", List.of());
        sqlGraph.put("lineage", List.of());
        capsule.put("sqlGraph", sqlGraph);

        Map<String, Object> semanticGraph = new LinkedHashMap<>();
        semanticGraph.put("entities", objects);
        semanticGraph.put("cases", List.of());
        semanticGraph.put("playbooks", List.of());
        semanticGraph.put("systems", List.of());
        semanticGraph.put("apiFlows", List.of());
        capsule.put("semanticGraph", semanticGraph);

        Map<String, Object> plannerRuntime = new LinkedHashMap<>();
        List<String> suggestedTables = new ArrayList<>();
        JsonNode suggestedTablesNode = insights.path("suggestedTables");
        if (suggestedTablesNode.isArray()) {
            for (JsonNode node : suggestedTablesNode) {
                if (node.isTextual() && !node.asText().isBlank()) {
                    suggestedTables.add(node.asText());
                }
                if (suggestedTables.size() >= 20) {
                    break;
                }
            }
        }
        List<String> suggestedQueries = new ArrayList<>();
        JsonNode suggestedPreparedQueriesNode = insights.path("suggestedPreparedQueries");
        if (suggestedPreparedQueriesNode.isArray()) {
            for (JsonNode node : suggestedPreparedQueriesNode) {
                if (node.isTextual() && !node.asText().isBlank()) {
                    suggestedQueries.add(compactJsonValue(node.asText(), 400));
                }
                if (suggestedQueries.size() >= 20) {
                    break;
                }
            }
        }
        plannerRuntime.put("suggestedTables", suggestedTables);
        plannerRuntime.put("suggestedPreparedQueries", suggestedQueries);
        plannerRuntime.put("hints", List.of("legacy db.semantic.catalog capsule"));
        capsule.put("plannerRuntime", plannerRuntime);
        return capsule;
    }

    private int resolvePlannerMaxObservationChars() {
        int yamlValue = mcpConfig == null ? 6000 : mcpConfig.getPlannerMaxObservationChars();
        int resolved = configResolver.resolveInt(this, "MCP_PLANNER_MAX_OBS_CHARS", yamlValue);
        return Math.max(1000, resolved);
    }

    private Object compactObservationJson(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        int max = resolvePlannerMaxObservationChars();
        JsonNode node = parseJson(json);
        if (!node.isObject()) {
            return compactJsonValue(json, max);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        int limit = 20;
        int count = 0;
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext() && count < limit) {
            Map.Entry<String, JsonNode> entry = fields.next();
            out.put(entry.getKey(), compactNode(entry.getValue()));
            count++;
        }
        return out;
    }

    private String resolveDbkgCapsuleJson(EngineSession session, String observedCapsuleJson) {
        if (!mcpConfig.getDb().semanticCatalogConfig().isKnowledgeCapsule()) {
            return "{}";
        }
        if (observedCapsuleJson != null && !observedCapsuleJson.isBlank() && !"{}".equals(observedCapsuleJson.trim())) {
            return observedCapsuleJson;
        }
        try {
            DbkgSupportService support = dbkgSupportServiceProvider.getIfAvailable();
            if (support == null) {
                return "{}";
            }
            String question = session == null ? "" : session.getUserText();
            List<String> tokens = support.normalizeTokens(question);
            Map<String, Object> capsule = support.buildMetadataCapsule(question, tokens, null, null);
            if (capsule == null || capsule.isEmpty()) {
                return "{}";
            }
            return JsonUtil.toJson(capsule);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Object summarizeObservationForPlanner(JsonNode parsed, String raw, int maxChars) {
        if (parsed == null || parsed.isNull()) {
            return compactJsonValue(raw, maxChars);
        }
        if (!parsed.isObject()) {
            return compactJsonValue(raw, maxChars);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : McpConstants.OBSERVATION_SUMMARY_FIELDS) {
            JsonNode value = parsed.get(key);
            if (value != null && !value.isNull()) {
                summary.put(key, compactNode(value));
            }
        }

        JsonNode rowsNode = parsed.get("rows");
        if (rowsNode != null && rowsNode.isArray()) {
            summary.putIfAbsent("rowCount", rowsNode.size());
            List<Object> rowsPreview = new ArrayList<>();
            for (int i = 0; i < Math.min(2, rowsNode.size()); i++) {
                rowsPreview.add(compactNode(rowsNode.get(i)));
            }
            summary.put("rowsPreview", rowsPreview);
        }

        if (summary.isEmpty()) {
            return compactObservationJson(raw);
        }
        return summary;
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return "";
        }
        return child.isTextual() ? child.asText() : child.toString();
    }

    private Object compactNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return compactJsonValue(node.asText(), 2000);
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isArray() || node.isObject()) {
            return node;
        }
        return compactJsonValue(node.toString(), 2000);
    }

    private String compactJsonValue(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private List<String> splitCsvLike(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("[,|]");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String token = part == null ? "" : part.trim();
            if (!token.isBlank()) {
                out.add(token);
            }
        }
        return out;
    }

    private record ObservationPayload(String json, boolean compacted, int rawChars, int finalChars, String dbkgCapsuleJson) {
    }

    private String resolveLegacyPrompt(String primaryKey, String legacyKey, String defaultValue) {
        String primary = configResolver.resolveString(this, primaryKey, defaultValue);
        if (primary != null && !primary.isBlank() && !defaultValue.equals(primary)) {
            return primary;
        }
        return configResolver.resolveString(this, legacyKey, defaultValue);
    }

    private PlannerTimeContext resolvePlannerTimeContext() {
        ZoneId systemZone = ZoneId.systemDefault();
        OffsetDateTime nowUtc = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime nowSystem = OffsetDateTime.now(systemZone);
        return new PlannerTimeContext(
                nowUtc.toLocalDate().toString(),
                nowUtc.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                String.valueOf(nowUtc.getYear()),
                "UTC",
                nowSystem.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                systemZone.getId());
    }

    private record PlannerTimeContext(
            String currentDate,
            String currentDateTime,
            String currentYear,
            String currentTimezone,
            String currentSystemDateTime,
            String currentSystemTimezone) {
    }

    private record PlannerPromptSet(String systemPrompt, String userPrompt, String source) {
    }
}
