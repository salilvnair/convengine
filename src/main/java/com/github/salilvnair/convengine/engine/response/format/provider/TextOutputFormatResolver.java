package com.github.salilvnair.convengine.engine.response.format.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.response.format.core.OutputFormatResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.model.PromptTemplate;
import com.github.salilvnair.convengine.model.ResponseTemplate;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            ResponseTemplate response,
            PromptTemplate template
    ) {
        session.addPromptTemplateVars();

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
                        .extra(session.promptTemplateVars())
                        .build();

        String systemPrompt = renderer.render(template.getSystemPrompt(), ctx);
        String userPrompt = renderer.render(template.getUserPrompt(), ctx);

        LlmInvocationContext.set(
                session.getConversationId(),
                session.getIntent(),
                session.getState()
        );

        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put(ConvEnginePayloadKey.SYSTEM_PROMPT, systemPrompt);
        inputPayload.put(ConvEnginePayloadKey.USER_PROMPT, userPrompt);
        inputPayload.put(ConvEnginePayloadKey.DERIVATION_HINT, safe(response.getDerivationHint()));
        inputPayload.put(ConvEnginePayloadKey.SESSION, session.eject());
        audit.audit(ConvEngineAuditStage.RESOLVE_RESPONSE_LLM_INPUT, session.getConversationId(), inputPayload);

        String text =
                llm.generateText(
                        systemPrompt + "\n\n" + userPrompt + "\n\n" +
                                safe(response.getDerivationHint()),
                        JsonUtil.toJson(session.contextDict())
                );
        session.setLastLlmOutput(text);
        session.setLastLlmStage("RESPONSE_TEXT");
        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put(ConvEnginePayloadKey.OUTPUT, text);
        audit.audit(ConvEngineAuditStage.RESOLVE_RESPONSE_LLM_OUTPUT, session.getConversationId(), outputPayload);

        session.setPayload(new TextPayload(text));
        session.getConversation().setLastAssistantJson(
                "{\"type\":\"TEXT\",\"value\":\"" + JsonUtil.escape(text) + "\"}"
        );
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
