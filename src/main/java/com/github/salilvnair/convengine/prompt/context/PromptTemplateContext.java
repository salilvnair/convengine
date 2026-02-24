package com.github.salilvnair.convengine.prompt.context;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.intent.AllowedIntent;
import com.github.salilvnair.convengine.prompt.annotation.PromptVar;
import lombok.*;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PromptTemplateContext {
    @PromptVar({ "context", "contextJson" })
    private String context;

    @PromptVar({ "user_input", "userInput", "user_text", "userText", "input_text", "inputText", "input", "user_message", "userMessage" })
    private String userInput;

    @PromptVar({ "schema", "json_schema", "schema_json", "data_schema", "dataSchema", "jsonSchema", "schemaJson" })
    private String schemaJson;

    @PromptVar({ "container_data", "containerData" })
    private String containerDataJson;

    @PromptVar({ "validation", "validation_tables" })
    private String validationJson;

    @PromptVar({ "allowed_intents", "allowedIntents" })
    private List<AllowedIntent> allowedIntents;

    @PromptVar({ "pending_clarification", "pendingClarification" })
    private String pendingClarification;

    @PromptVar({ "conversation_history", "conversationHistory", "history" })
    private String conversationHistory;

    @PromptVar({ "mcp_tools", "tools", "available_tools", "tool_list", "mcpTools" })
    private String mcpTools;

    @PromptVar({ "mcp_observations", "observations", "mcpObservations" })
    private String mcpObservations;

    private Map<String, Object> extra;

    // Metadata fields natively attached to the Context for Audit Traces
    private String templateName;
    private String systemPrompt;
    private String userPrompt;

    private EngineSession session;

}
