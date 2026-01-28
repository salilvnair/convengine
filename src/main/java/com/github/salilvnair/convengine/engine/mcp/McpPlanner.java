package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.mcp.model.McpPlan;
import com.github.salilvnair.convengine.engine.mcp.model.McpObservation;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.repo.PromptTemplateRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class McpPlanner {

    private static final String PURPOSE = "MCP_PLANNER";

    private final PromptTemplateRepository promptRepo;
    private final PromptTemplateRenderer renderer;
    private final LlmClient llm;
    private final AuditService audit;

    private final ObjectMapper mapper = new ObjectMapper();

    public McpPlan plan(EngineSession session, List<CeMcpTool> tools, List<McpObservation> observations) {

        CePromptTemplate template =
                promptRepo.findFirstByEnabledTrueAndPurposeAndIntentCodeIsNullOrderByCreatedAtDesc(PURPOSE)
                        .orElseThrow(() -> new IllegalStateException("Missing ce_prompt_template purpose=" + PURPOSE));

        String toolsJson = JsonUtil.toJson(tools.stream().map(t ->
                new ToolView(t.getToolCode(), t.getToolGroup(), t.getDescription())
        ).toList());

        String obsJson = JsonUtil.toJson(observations);

        PromptTemplateContext ctx = PromptTemplateContext.builder()
                                    .context(session.getContextJson())
                                    .userInput(session.getUserText())
                                    .mcpTools(toolsJson)
                                    .mcpObservations(obsJson)
                                    .build();

        String systemPrompt = renderer.render(template.getSystemPrompt(), ctx);
        String userPrompt = renderer.render(template.getUserPrompt(), ctx);

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

        audit.audit(
                "MCP_PLAN_LLM_INPUT",
                session.getConversationId(),
                "{\"system_prompt\":\"" + JsonUtil.escape(systemPrompt) +
                        "\",\"user_prompt\":\"" + JsonUtil.escape(userPrompt) +
                        "\",\"schema\":\"" + JsonUtil.escape(schema) + "\"}"
        );

        LlmInvocationContext.set(
                session.getConversationId(),
                session.getIntent(),
                session.getState()
        );

        String out = llm.generateJson(systemPrompt + "\n\n" + userPrompt, schema, session.getContextJson());

        audit.audit(
                "MCP_PLAN_LLM_OUTPUT",
                session.getConversationId(),
                "{\"json\":\"" + JsonUtil.escape(out) + "\"}"
        );

        try {
            return mapper.readValue(out, McpPlan.class);
        } catch (Exception e) {
            return new McpPlan("ANSWER", null, java.util.Map.of(),
                    "I couldn't plan tool usage safely. Can you rephrase your question?");
        }
    }

    private record ToolView(String tool_code, String tool_group, String description) {}
}
