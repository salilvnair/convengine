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
    @PromptVar({ "context", "contextJson", "context_json" })
    private String context;

    @PromptVar({ "user_input", "userInput", "user_text", "userText", "input_text", "inputText", "input", "user_message", "userMessage" })
    private String userInput;

    @PromptVar({ "resolved_user_input", "resolvedUserInput", "resolved_input", "resolvedInput", "effective_input","effectiveInput",
            "effective_user_input", "effectiveUserInput",
            "effectiveInput", "final_user_input", "finalUserInput" })
    private String resolvedUserInput;

    @PromptVar({ "standalone_query", "standaloneQuery", "rewritten_query", "rewrittenQuery", "query_rewrite",
            "queryRewrite" })
    private String standaloneQuery;

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

    @PromptVar({ "question", "query_question", "queryQuestion" })
    private String question;

    @PromptVar({ "selected_entity", "selectedEntity", "entity" })
    private String selectedEntity;

    @PromptVar({ "selected_entity_description", "selectedEntityDescription" })
    private String selectedEntityDescription;

    @PromptVar({ "selected_entity_fields_json", "selectedEntityFieldsJson" })
    private String selectedEntityFieldsJson;

    @PromptVar({ "selected_entity_allowed_values_json", "selectedEntityAllowedValuesJson" })
    private String selectedEntityAllowedValuesJson;

    @PromptVar({ "allowed_entities", "allowedEntities", "allowed_entities_json", "allowedEntitiesJson" })
    private String allowedEntitiesJson;

    @PromptVar({ "candidate_entities_json", "candidateEntitiesJson" })
    private String candidateEntitiesJson;

    @PromptVar({ "candidate_tables_json", "candidateTablesJson" })
    private String candidateTablesJson;

    @PromptVar({ "join_path_json", "joinPathJson" })
    private String joinPathJson;

    @PromptVar({ "current_date", "currentDate" })
    private String currentDate;

    @PromptVar({ "current_datetime", "currentDateTime" })
    private String currentDateTime;

    @PromptVar({ "current_year", "currentYear" })
    private String currentYear;

    @PromptVar({ "current_timezone", "currentTimezone" })
    private String currentTimezone;

    @PromptVar({ "current_system_datetime", "currentSystemDateTime" })
    private String currentSystemDateTime;

    @PromptVar({ "current_system_timezone", "currentSystemTimezone" })
    private String currentSystemTimezone;

    private Map<String, Object> extra;

    // Metadata fields natively attached to the Context for Audit Traces
    private String templateName;
    private String systemPrompt;
    private String userPrompt;

    private EngineSession session;

}
