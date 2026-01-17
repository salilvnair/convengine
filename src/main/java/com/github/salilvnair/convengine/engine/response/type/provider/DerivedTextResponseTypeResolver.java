package com.github.salilvnair.convengine.engine.response.type.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.response.format.core.OutputFormatResolver;
import com.github.salilvnair.convengine.engine.response.format.factory.OutputFormatResolverFactory;
import com.github.salilvnair.convengine.engine.response.type.core.ResponseTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.repo.PromptTemplateRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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

        CePromptTemplate template =
                promptRepo
                        .findFirstByEnabledTrueAndPurposeAndIntentCodeOrderByCreatedAtDesc(
                                purpose,
                                session.getIntent()
                        )
                        .orElseGet(() ->
                                promptRepo
                                        .findFirstByEnabledTrueAndPurposeAndIntentCodeIsNullOrderByCreatedAtDesc(
                                                purpose
                                        )
                                        .orElseThrow(() ->
                                                new IllegalStateException(
                                                        "No ce_prompt_template found for purpose=" +
                                                                purpose + ", intent=" + session.getIntent()
                                                )
                                        )
                        );



        OutputFormatResolver resolver = formatFactory.get(response.getOutputFormat());

        audit.audit(
                "RESOLVE_RESPONSE_SELECTED",
                session.getConversationId(),
                "{\"templateId\":" + template.getTemplateId() +
                        ",\"purpose\":\"" + JsonUtil.escape(purpose) +
                        "\",\"intent\":\"" + JsonUtil.escape(session.getIntent()) +
                        "\",\"outputFormat\":\"" + JsonUtil.escape(response.getOutputFormat()) +
                        "\",\"resolver\":\"" + resolver.getClass().getSimpleName() + "\"}"
        );

        resolver.resolve(session, response, template);
    }
}
