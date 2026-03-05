package com.github.salilvnair.convengine.engine.response.type.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.constants.OutputFormatConstants;
import com.github.salilvnair.convengine.engine.constants.ResponseTypeConstants;
import com.github.salilvnair.convengine.engine.response.type.core.ResponseTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.PromptTemplate;
import com.github.salilvnair.convengine.model.ResponseTemplate;
import com.github.salilvnair.convengine.template.ThymeleafTemplateRenderer;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thymeleaf.exceptions.TemplateInputException;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExactTextResponseTypeResolver implements ResponseTypeResolver {

    private final AuditService audit;
    private final ThymeleafTemplateRenderer templateRenderer;

    @Override
    public String type() {
        return ResponseTypeConstants.EXACT;
    }

    @Override
    public void resolve(EngineSession session, PromptTemplate template, ResponseTemplate response) {

        String rawTemplate = response.getExactText() == null ? "" : response.getExactText();
        String text;
        try {
            text = templateRenderer.render(rawTemplate, session, buildRenderMetadata(response, template));
        } catch (TemplateInputException ex) {
            text = fallbackText(session, rawTemplate);
            auditRenderFallback(session, rawTemplate, ex.getClass().getName(), ex.getMessage());
        }

        // Defensive fallback for malformed templates that render blank without throwing parse exceptions.
        if (text != null && text.isBlank() && looksLikeThymeleafTemplate(rawTemplate)) {
            String fallback = fallbackText(session, rawTemplate);
            if (!fallback.equals(rawTemplate) && !fallback.isBlank()) {
                text = fallback;
                auditRenderFallback(
                        session,
                        rawTemplate,
                        "BLANK_RENDERED_OUTPUT",
                        "Template rendered blank; falling back to context.mcp.finalAnswer"
                );
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.TEXT, text);
        audit.audit(ConvEngineAuditStage.RESPONSE_EXACT, session.getConversationId(), payload);

        if (OutputFormatConstants.JSON.equalsIgnoreCase(response.getOutputFormat())) {
            session.setPayload(new JsonPayload(text));
            var parsed = JsonUtil.parseOrNull(text);
            if (parsed.isObject() || parsed.isArray()) {
                session.getConversation().setLastAssistantJson(text);
            } else {
                session.getConversation().setLastAssistantJson(
                        "{\"type\":\"JSON_TEXT\",\"value\":\"" + JsonUtil.escape(text) + "\"}"
                );
            }
        } else {
            session.setPayload(new TextPayload(text));
            session.getConversation().setLastAssistantJson(
                    "{\"type\":\"TEXT\",\"value\":\"" + JsonUtil.escape(text) + "\"}"
            );
        }
        session.setLastLlmOutput(text);
        session.setLastLlmStage("RESPONSE_EXACT");
    }

    private String fallbackText(EngineSession session, String rawTemplate) {
        Object contextObj = session == null ? null : session.contextDict();
        if (contextObj instanceof Map<?, ?> contextMap) {
            Object mcpObj = contextMap.get("mcp");
            if (mcpObj instanceof Map<?, ?> mcpMap) {
                Object finalAnswer = mcpMap.get("finalAnswer");
                if (finalAnswer != null) {
                    String value = String.valueOf(finalAnswer).trim();
                    if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                        return value;
                    }
                }
            }
        }
        return rawTemplate == null ? "" : rawTemplate;
    }

    private boolean looksLikeThymeleafTemplate(String rawTemplate) {
        if (rawTemplate == null) {
            return false;
        }
        return rawTemplate.contains("th:") || rawTemplate.contains("[(") || rawTemplate.contains("[[");
    }

    private void auditRenderFallback(EngineSession session, String rawTemplate, String errorClass, String errorMessage) {
        Map<String, Object> renderFailurePayload = new LinkedHashMap<>();
        renderFailurePayload.put("component", "response-exact");
        renderFailurePayload.put("fallbackUsed", true);
        renderFailurePayload.put("errorClass", errorClass);
        renderFailurePayload.put("errorMessage", errorMessage);
        renderFailurePayload.put("templatePreview", rawTemplate != null && rawTemplate.length() > 500
                ? rawTemplate.substring(0, 500) + "..."
                : rawTemplate);
        audit.audit(ConvEngineAuditStage.PROMPT_RENDERING, session.getConversationId(), renderFailurePayload);
    }

    private Map<String, Object> buildRenderMetadata(ResponseTemplate response, PromptTemplate template) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (response != null) {
            metadata.put("outputFormat", response.getOutputFormat());
            metadata.put("responseType", response.getResponseType());
            metadata.put("derivationHint", response.getDerivationHint());
            metadata.put("jsonSchema", response.getJsonSchema());
            metadata.put("exactText", response.getExactText());
        }
        if (template != null) {
            metadata.put("promptTemplateId", template.getTemplateId());
            metadata.put("promptResponseType", template.getResponseType());
            metadata.put("promptTemplateDesc", template.getTemplateDesc());
        }
        return metadata;
    }
}
