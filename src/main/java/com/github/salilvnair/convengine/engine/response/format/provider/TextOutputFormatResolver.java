package com.github.salilvnair.convengine.engine.response.format.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.response.format.core.OutputFormatResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TextOutputFormatResolver implements OutputFormatResolver {

    private final LlmClient llm;
    private final PromptTemplateRenderer renderer;
    private final AuditService audit;

    @Override
    public String format() {
        return "TEXT";
    }

    @Override
    public void resolve(
            EngineSession session,
            CeResponse response,
            CePromptTemplate template
    ) {
        String historyJson = JsonUtil.toJson(session.conversionHistory());

        PromptTemplateContext ctx =
                PromptTemplateContext.builder()
                        .context(session.getContextJson())
                        .userInput(session.getUserText())
                        .schemaJson(session.getResolvedSchema() != null
                                ? session.getResolvedSchema().getJsonSchema()
                                : null)
                        .containerDataJson(session.getContainerDataJson())
                        .validationJson(session.getValidationTablesJson())
                        .conversationHistory(historyJson)
                        .build();

        String systemPrompt = renderer.render(template.getSystemPrompt(), ctx);
        String userPrompt = renderer.render(template.getUserPrompt(), ctx);

        LlmInvocationContext.set(
                session.getConversationId(),
                session.getIntent(),
                session.getState()
        );

        audit.audit(
                "RESOLVE_RESPONSE_LLM_INPUT",
                session.getConversationId(),
                "{\"system_prompt\":\"" + JsonUtil.escape(systemPrompt) +
                        "\",\"user_prompt\":\"" + JsonUtil.escape(userPrompt) +
                        "\",\"derivation_hint\":\"" + JsonUtil.escape(safe(response.getDerivationHint())) +
                        "\",\"context\":\"" + JsonUtil.escape(session.getContextJson()) + "\"}"
        );

        String text =
                llm.generateText(
                        systemPrompt + "\n\n" + userPrompt + "\n\n" +
                                safe(response.getDerivationHint()),
                        session.getContextJson()
                );
        audit.audit(
                "RESOLVE_RESPONSE_LLM_OUTPUT",
                session.getConversationId(),
                "{\"text\":\"" + JsonUtil.escape(text) + "\"}"
        );

        session.setPayload(new TextPayload(text));
        session.getConversation().setLastAssistantJson(
                "{\"type\":\"TEXT\",\"value\":\"" + JsonUtil.escape(text) + "\"}"
        );
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
