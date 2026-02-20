package com.github.salilvnair.convengine.engine.response.type.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.response.format.core.OutputFormatResolver;
import com.github.salilvnair.convengine.engine.response.format.factory.OutputFormatResolverFactory;
import com.github.salilvnair.convengine.engine.response.type.core.ResponseTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.model.PromptTemplate;
import com.github.salilvnair.convengine.model.ResponseTemplate;
import com.github.salilvnair.convengine.repo.PromptTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DerivedTextResponseTypeResolver implements ResponseTypeResolver {

    private final OutputFormatResolverFactory formatFactory;
    private final AuditService audit;

    @Override
    public String type() {
        return "DERIVED";
    }

    @Override
    public void resolve(EngineSession session, PromptTemplate template, ResponseTemplate response) {
        OutputFormatResolver resolver = formatFactory.get(response.getOutputFormat());
        Map<String, Object> payload = new LinkedHashMap<>();
        if(template.getTemplateId() != null) {
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.TEMPLATE_ID, template.getTemplateId());
        }
        if(template.getTemplateId() != null) {
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.TEMPLATE_DESC, template.getTemplateDesc());
        }
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.OUTPUT_FORMAT, response.getOutputFormat());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RESOLVER, resolver.getClass().getSimpleName());
        audit.audit(ConvEngineAuditStage.RESOLVE_RESPONSE_SELECTED, session.getConversationId(), payload);
        resolver.resolve(session, response, template);
    }

    private boolean matches(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private boolean matchesOrNull(String left, String right) {
        return left == null || matches(left, right);
    }
}
