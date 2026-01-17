package com.github.salilvnair.convengine.engine.response.type.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.response.type.core.ResponseTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExactTextResponseTypeResolver implements ResponseTypeResolver {

    private final AuditService audit;

    @Override
    public String type() {
        return "EXACT";
    }

    @Override
    public void resolve(EngineSession session, CeResponse response) {

        String text = response.getExactText();

        audit.audit(
                "RESPONSE_EXACT",
                session.getConversationId(),
                "{\"text\":\"" + JsonUtil.escape(text) + "\"}"
        );

        session.setPayload(new TextPayload(text));
        session.getConversation().setLastAssistantJson(
                "{\"type\":\"TEXT\",\"value\":\"" + JsonUtil.escape(text) + "\"}"
        );
    }
}
