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

    @PromptVar({ "user_input", "userInput" })
    private String userInput;

    @PromptVar({ "schema", "json_schema" })
    private String schemaJson;

    @PromptVar({ "container_data", "containerData" })
    private String containerDataJson;

    @PromptVar({ "validation", "validation_tables" })
    private String validationJson;

    @PromptVar({ "allowed_intents" })
    private List<AllowedIntent> allowedIntents;

    @PromptVar({ "pending_clarification" })
    private String pendingClarification;

    @PromptVar({ "conversation_history" })
    private String conversationHistory;

    @PromptVar({ "mcp_tools" })
    private String mcpTools;

    @PromptVar({ "mcp_observations" })
    private String mcpObservations;

    private Map<String, Object> extra;

    // Metadata fields natively attached to the Context for Audit Traces
    private String templateName;
    private String systemPrompt;
    private String userPrompt;

    private EngineSession session;

}
