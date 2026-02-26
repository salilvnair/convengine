package com.github.salilvnair.convengine.engine.mcp;

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
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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

    private final ObjectMapper mapper = new ObjectMapper();

    public McpPlan plan(EngineSession session, List<CeMcpTool> tools, List<McpObservation> observations) {
        PlannerPromptSet promptSet = resolvePlannerPromptSet(session);

        String toolsJson = JsonUtil.toJson(
                tools.stream().map(t -> new ToolView(t.getToolCode(), t.getToolGroup(), t.getDescription())).toList());

        String obsJson = JsonUtil.toJson(observations);

        PromptTemplateContext ctx = PromptTemplateContext.builder()
                .templateName("McpPlanner")
                .systemPrompt(promptSet.systemPrompt())
                .userPrompt(promptSet.userPrompt())
                .context(session.getContextJson())
                .userInput(session.getUserText())
                .mcpTools(toolsJson)
                .mcpObservations(obsJson)
                .extra(session.promptTemplateVars())
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
        audit.audit(ConvEngineAuditStage.MCP_PLAN_LLM_INPUT, session.getConversationId(), inputPayload);

        LlmInvocationContext.set(
                session.getConversationId(),
                session.getIntent(),
                session.getState());

        String out = llm.generateJson(systemPrompt + "\n\n" + userPrompt, schema, session.getContextJson());

        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put(ConvEnginePayloadKey.JSON, out);
        audit.audit(ConvEngineAuditStage.MCP_PLAN_LLM_OUTPUT, session.getConversationId(), outputPayload);

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
