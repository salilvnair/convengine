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
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class McpPlanner {

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

    private final ObjectMapper mapper = new ObjectMapper();

    public McpPlan plan(EngineSession session, List<CeMcpTool> tools, List<McpObservation> observations) {
        PlannerPromptSet promptSet = resolvePlannerPromptSet(session);

        String toolsJson = JsonUtil.toJson(
                tools.stream().map(t -> new ToolView(t.getToolCode(), t.getToolGroup(), t.getDescription())).toList());

        ObservationPayload obsPayload = buildObservationsPayload(observations);
        String obsJson = obsPayload.json();

        Map<String, Object> extraVars = new LinkedHashMap<>(session.promptTemplateVars());
        extraVars.remove("mcp_observations");
        extraVars.remove("MCP_OBSERVATIONS");
        PromptTemplateContext ctx = PromptTemplateContext.builder()
                .templateName("McpPlanner")
                .systemPrompt(promptSet.systemPrompt())
                .userPrompt(promptSet.userPrompt())
                .context(session.getContextJson())
                .userInput(session.getUserText())
                .resolvedUserInput(session.getResolvedUserInput())
                .standaloneQuery(session.getStandaloneQuery())
                .mcpTools(toolsJson)
                .mcpObservations(obsJson)
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
        audit.audit(ConvEngineAuditStage.MCP_PLAN_LLM_INPUT, session.getConversationId(), inputPayload);
        verbosePublisher.publish(session, "McpPlanner", "MCP_PLAN_LLM_INPUT", null, null, false, inputPayload);

        LlmInvocationContext.set(
                session.getConversationId(),
                session.getIntent(),
                session.getState());

        String out;
        try {
            out = llm.generateJson(systemPrompt + "\n\n" + userPrompt, schema, session.getContextJson());
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
            return mapper.readValue(out, McpPlan.class);
        } catch (Exception e) {
            return new McpPlan(McpConstants.ACTION_ANSWER, null, java.util.Map.of(),
                    McpConstants.FALLBACK_PLAN_ERROR);
        }
    }

    private record ToolView(String tool_code, String tool_group, String description) {
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
        if (raw.length() <= maxChars) {
            return new ObservationPayload(raw, false, raw.length(), raw.length());
        }
        List<Map<String, Object>> compact = new ArrayList<>();
        for (McpObservation observation : recentObservations) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("toolCode", observation.toolCode());
            entry.put("json", compactObservationJson(observation.json()));
            compact.add(entry);
        }
        String compactJson = JsonUtil.toJson(compact);
        return new ObservationPayload(compactJson, true, raw.length(), compactJson.length());
    }

    private int resolvePlannerMaxObservationChars() {
        int yamlValue = mcpConfig == null ? 6000 : mcpConfig.getPlannerMaxObservationChars();
        int resolved = configResolver.resolveInt(this, "MCP_PLANNER_MAX_OBS_CHARS", yamlValue);
        return Math.max(1000, resolved);
    }

    private String compactObservationJson(String json) {
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
        String rendered = JsonUtil.toJson(out);
        return compactJsonValue(rendered, max);
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
            try {
                return mapper.writeValueAsString(node);
            } catch (Exception e) {
                return node.toString();
            }
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

    private String resolveLegacyPrompt(String primaryKey, String legacyKey, String defaultValue) {
        String primary = configResolver.resolveString(this, primaryKey, defaultValue);
        if (primary != null && !primary.isBlank() && !defaultValue.equals(primary)) {
            return primary;
        }
        return configResolver.resolveString(this, legacyKey, defaultValue);
    }

    private record PlannerPromptSet(String systemPrompt, String userPrompt, String source) {
    }
}
