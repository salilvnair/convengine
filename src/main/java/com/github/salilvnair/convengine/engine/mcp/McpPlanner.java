package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class McpPlanner {

    private final PromptTemplateRepository promptRepo;
    private final PromptTemplateRenderer renderer;
    private final LlmClient llm;
    private final AuditService audit;
    private String SYSTEM_PROMPT;
    private String USER_PROMPT;
    private final CeConfigResolver configResolver;

    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        SYSTEM_PROMPT = configResolver.resolveString(this, "SYSTEM_PROMPT", """

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

                
                """);
        USER_PROMPT = configResolver.resolveString(this, "USER_PROMPT", """
                
                
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
                
                
                """);
    }

    public McpPlan plan(EngineSession session, List<CeMcpTool> tools, List<McpObservation> observations) {


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

        String systemPrompt = renderer.render(SYSTEM_PROMPT, ctx);
        String userPrompt = renderer.render(USER_PROMPT, ctx);

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
        inputPayload.put("templateFromCeConfig (McpPlanner)", "USER_PROMPT, SYSTEM_PROMPT");
        inputPayload.put("system_prompt", systemPrompt);
        inputPayload.put("user_prompt", userPrompt);
        inputPayload.put("schema", schema);
        audit.audit("MCP_PLAN_LLM_INPUT", session.getConversationId(), inputPayload);

        LlmInvocationContext.set(
                session.getConversationId(),
                session.getIntent(),
                session.getState()
        );

        String out = llm.generateJson(systemPrompt + "\n\n" + userPrompt, schema, session.getContextJson());

        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put("json", out);
        audit.audit("MCP_PLAN_LLM_OUTPUT", session.getConversationId(), outputPayload);

        try {
            return mapper.readValue(out, McpPlan.class);
        } catch (Exception e) {
            return new McpPlan("ANSWER", null, java.util.Map.of(),
                    "I couldn't plan tool usage safely. Can you rephrase your question?");
        }
    }

    private record ToolView(String tool_code, String tool_group, String description) {}
}
