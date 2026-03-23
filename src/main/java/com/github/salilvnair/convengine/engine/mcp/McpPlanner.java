package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
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
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
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
                - For semantic flow, use this exact chain when tools are available:
                  `db.semantic.interpret` -> `db.semantic.query` -> `postgres.query`
                - Do not skip steps in the semantic chain.
                - If `db.semantic.interpret` or `db.semantic.query` observation returns
                  `needsClarification=true`, STOP tool calls and return ANSWER using `clarificationQuestion`.
                - `action` MUST be exactly one of: CALL_TOOL or ANSWER.
                - Never return values like clarification_required / needs_clarification / clarify.
                - Any non-contract action is invalid.

                Return JSON ONLY.

            """;

    private static final String DEFAULT_DB_USER_PROMPT = """

            User input:
            {{user_input}}

            MCP Context:
            {{context.mcp}}

            Available MCP tools:
            {{mcp_tools}}

            Existing MCP observations (if any):
            {{mcp_observations}}

            If semantic tools are available, follow:
            `db.semantic.interpret` -> `db.semantic.query` -> `postgres.query`.
            Stop and return ANSWER when `needsClarification=true`.

            Return JSON EXACTLY in this schema:
            {
              "action": "CALL_TOOL" | "ANSWER",
              "tool_code": "<tool_code_or_null>",
              "args": { },
              "answer": "<text_or_null>",
              "operation_tag": "<POLICY_RESTRICTED_OPERATION_or_null>"
            }
            `action` MUST be exactly CALL_TOOL or ANSWER. No other value is allowed.

            """;

    private final StaticConfigurationCacheService staticCacheService;
    private final PromptTemplateRenderer renderer;
    private final LlmClient llm;
    private final AuditService audit;
    private final CeConfigResolver configResolver;
    private final VerboseMessagePublisher verbosePublisher;

    private final ObjectMapper mapper = new ObjectMapper();

    public McpPlan plan(EngineSession session, List<CeMcpTool> tools, List<McpObservation> observations) {
        PlannerPromptSet promptSet = resolvePlannerPromptSet(session);
        List<CeMcpTool> plannerTools = filterToolsByPlannerPrompt(tools, promptSet);

        String toolsJson = JsonUtil.toJson(
                plannerTools.stream().map(t -> new ToolView(t.getToolCode(), t.getToolGroup(), t.getDescription())).toList());

        ObservationPayload obsPayload = buildObservationsPayload(observations);
        String obsJson = obsPayload.json();

        Map<String, Object> extraVars = new LinkedHashMap<>(session.promptTemplateVars());
        extraVars.remove("mcp_observations");
        extraVars.remove("MCP_OBSERVATIONS");
        Map<String, Object> contextMap = session.contextDict();
        extraVars.put("context", contextMap);
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
                  "required":["action","tool_code","args","answer","operation_tag"],
                  "properties":{
                    "action":{"type":"string"},
                    "tool_code":{"type":["string","null"]},
                    "args":{"type":"object"},
                    "answer":{"type":["string","null"]},
                    "operation_tag":{"type":["string","null"]}
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
            McpPlan normalized = normalizePlan(parsed);
            return rewriteSemanticPlan(normalized, observations);
        } catch (Exception e) {
            return new McpPlan(McpConstants.ACTION_ANSWER, null, java.util.Map.of(),
                    McpConstants.FALLBACK_PLAN_ERROR, null);
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
            return new McpPlan(McpConstants.ACTION_ANSWER, null, Map.of(), McpConstants.FALLBACK_PLAN_ERROR, null);
        }

        String rawAction = trimToNull(plan.action());
        if (rawAction != null) {
            String upper = rawAction.toUpperCase(Locale.ROOT);
            if (!McpConstants.ACTION_CALL_TOOL.equals(upper) && !McpConstants.ACTION_ANSWER.equals(upper)) {
                return new McpPlan(
                        McpConstants.ACTION_ANSWER,
                        null,
                        Map.of(),
                        McpConstants.FALLBACK_PLAN_ERROR,
                        null
                );
            }
        }

        String toolCode = trimToNull(plan.tool_code());
        String answer = trimToNull(plan.answer());
        String operationTag = trimToNull(plan.operation_tag());
        Map<String, Object> args = plan.args() == null ? Map.of() : new LinkedHashMap<>(plan.args());
        String action = normalizeAction(rawAction, toolCode, answer);

        if (McpConstants.ACTION_CALL_TOOL.equals(action)) {
            if (toolCode == null) {
                return new McpPlan(McpConstants.ACTION_ANSWER, null, Map.of(), McpConstants.FALLBACK_UNSAFE_NEXT_STEP, null);
            }
        } else {
            args = Map.of();
        }

        return new McpPlan(action, toolCode, args, answer, operationTag);
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

    private McpPlan rewriteSemanticPlan(McpPlan plan, List<McpObservation> observations) {
        if (plan == null || !McpConstants.ACTION_CALL_TOOL.equalsIgnoreCase(trimToNull(plan.action()))) {
            return plan;
        }
        String toolCode = trimToNull(plan.tool_code());
        if (toolCode == null) {
            return plan;
        }
        String normalizedToolCode = toolCode.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> args = plan.args() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(plan.args());
        if ("db.semantic.query".equalsIgnoreCase(toolCode) && !args.containsKey("canonicalIntent") && !args.containsKey("canonical_intent")) {
            Map<String, Object> canonicalIntent = extractCanonicalIntent(args, observations);
            if (!canonicalIntent.isEmpty()) {
                args.put("canonicalIntent", canonicalIntent);
                args.remove("resolvedPlan");
                args.remove("resolved_plan");
                return new McpPlan(McpConstants.ACTION_CALL_TOOL, "db.semantic.query", args, null, plan.operation_tag());
            }
        }
        return plan;
    }

    private Map<String, Object> extractCanonicalIntent(Map<String, Object> args, List<McpObservation> observations) {
        Map<String, Object> fromArgs = asMap(args.get("canonicalIntent"));
        if (!fromArgs.isEmpty()) {
            return fromArgs;
        }
        fromArgs = asMap(args.get("canonical_intent"));
        if (!fromArgs.isEmpty()) {
            return fromArgs;
        }
        if (observations == null || observations.isEmpty()) {
            return Map.of();
        }
        for (int i = observations.size() - 1; i >= 0; i--) {
            McpObservation observation = observations.get(i);
            if (observation == null || observation.toolCode() == null
                    || !"db.semantic.interpret".equalsIgnoreCase(observation.toolCode())) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(observation.json() == null ? "{}" : observation.json());
                JsonNode canonicalIntent = node.path("canonicalIntent");
                if (!canonicalIntent.isMissingNode() && canonicalIntent.isObject()) {
                    return mapper.convertValue(canonicalIntent, new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
                }
            } catch (Exception ignored) {
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
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
        String system = configResolver.resolveString(this, "SYSTEM_PROMPT", DEFAULT_DB_SYSTEM_PROMPT);
        String user = configResolver.resolveString(this, "USER_PROMPT", DEFAULT_DB_USER_PROMPT);
        return new PlannerPromptSet(system, user,
                "ce_config(SYSTEM_PROMPT,USER_PROMPT)");
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
        for (McpObservation observation : recentObservations) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("toolCode", observation.toolCode());

            JsonNode parsed = parseJson(observation.json());
            String parsedRaw = JsonUtil.toJson(parsed);
            entry.put("json", summarizeObservationForPlanner(parsed, parsedRaw, maxChars));

            String signature = JsonUtil.toJson(entry);
            if (seenEntries.add(signature)) {
                compact.add(entry);
            }
        }

        String compactJson = JsonUtil.toJson(compact);
        return new ObservationPayload(compactJson, raw.length() > maxChars, raw.length(), compactJson.length());
    }

    private int resolvePlannerMaxObservationChars() {
        int yamlValue = 6000;
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
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext() && count < limit) {
            Map.Entry<String, JsonNode> entry = fields.next();
            out.put(entry.getKey(), compactNode(entry.getValue()));
            count++;
        }
        return out;
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

    private record ObservationPayload(String json, boolean compacted, int rawChars, int finalChars) {
    }

    private PlannerTimeContext resolvePlannerTimeContext() {
        ZoneId systemZone = ZoneId.systemDefault();
        OffsetDateTime nowUtc = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime nowSystem = OffsetDateTime.now(systemZone);
        return new PlannerTimeContext(
                nowSystem.toLocalDate().toString(),
                nowSystem.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                String.valueOf(nowSystem.getYear()),
                systemZone.getId(),
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
