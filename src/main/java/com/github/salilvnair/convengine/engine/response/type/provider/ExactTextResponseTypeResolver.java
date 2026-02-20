package com.github.salilvnair.convengine.engine.response.type.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.response.type.core.ResponseTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.PromptTemplate;
import com.github.salilvnair.convengine.model.ResponseTemplate;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExactTextResponseTypeResolver implements ResponseTypeResolver {

    private final AuditService audit;

    @Override
    public String type() {
        return "EXACT";
    }

    @Override
    public void resolve(EngineSession session, PromptTemplate template, ResponseTemplate response) {

        String text = response.getExactText() == null ? "" : response.getExactText();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.TEXT, text);
        audit.audit(ConvEngineAuditStage.RESPONSE_EXACT, session.getConversationId(), payload);

        if ("JSON".equalsIgnoreCase(response.getOutputFormat())) {
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
}
