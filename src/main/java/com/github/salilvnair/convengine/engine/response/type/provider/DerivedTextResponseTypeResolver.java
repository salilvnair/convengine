package com.github.salilvnair.convengine.engine.response.type.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.response.format.core.OutputFormatResolver;
import com.github.salilvnair.convengine.engine.response.format.factory.OutputFormatResolverFactory;
import com.github.salilvnair.convengine.engine.response.type.core.ResponseTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeResponse;
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
    private final PromptTemplateRepository promptRepo;
    private final AuditService audit;

    @Override
    public String type() {
        return "DERIVED";
    }

    @Override
    public void resolve(EngineSession session, CeResponse response) {

        String purpose = response.getOutputFormat() + "_RESPONSE";

        CePromptTemplate template = promptRepo.findAll().stream()
                .filter(t -> Boolean.TRUE.equals(t.getEnabled()))
                .filter(t -> purpose.equalsIgnoreCase(t.getPurpose()))
                .filter(t -> matchesOrNull(t.getIntentCode(), session.getIntent()))
                .filter(t -> matchesOrNull(t.getStateCode(), session.getState()) || matches(t.getStateCode(), "ANY"))
                .max(Comparator.comparingInt(t -> score(t, session)))
                .orElseThrow(() ->
                        new IllegalStateException(
                                "No ce_prompt_template found for purpose=" +
                                        purpose + ", intent=" + session.getIntent() + ", state=" + session.getState()
                        )
                );



        OutputFormatResolver resolver = formatFactory.get(response.getOutputFormat());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("templateId", template.getTemplateId());
        payload.put("purpose", purpose);
        payload.put("intent", session.getIntent());
        payload.put("outputFormat", response.getOutputFormat());
        payload.put("resolver", resolver.getClass().getSimpleName());
        audit.audit("RESOLVE_RESPONSE_SELECTED", session.getConversationId(), payload);

        resolver.resolve(session, response, template);
    }

    private int score(CePromptTemplate template, EngineSession session) {
        int intentScore = matches(template.getIntentCode(), session.getIntent()) ? 2 : 1;
        int stateScore = matches(template.getStateCode(), session.getState())
                ? 2
                : (matches(template.getStateCode(), "ANY") ? 1 : 0);
        return (intentScore * 10) + (stateScore * 5);
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
