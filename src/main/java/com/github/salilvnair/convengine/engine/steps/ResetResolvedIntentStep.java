package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(IntentResolutionStep.class)
@MustRunBefore(FallbackIntentStateStep.class)
public class ResetResolvedIntentStep implements EngineStep {

    private final AuditService audit;
    private final ConversationRepository conversationRepository;
    private final CeConfigResolver configResolver;
    private Set<String> resetIntentCodes = Set.of("RESET_SESSION");

    @PostConstruct
    void init() {
        String configured = configResolver.resolveString(this, "RESET_INTENT_CODES", "RESET_SESSION");
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(String::toUpperCase)
                .forEach(out::add);
        if (!out.isEmpty()) {
            this.resetIntentCodes = out;
        }
    }

    @Override
    public StepResult execute(EngineSession session) {
        String intent = session.getIntent();
        if (intent == null || !resetIntentCodes.contains(intent.trim().toUpperCase())) {
            return new StepResult.Continue();
        }

        session.resetForConversationRestart();
        session.getConversation().setStatus("RUNNING");
        session.getConversation().setIntentCode("UNKNOWN");
        session.getConversation().setStateCode("UNKNOWN");
        session.getConversation().setContextJson("{}");
        session.getConversation().setInputParamsJson("{}");
        session.getConversation().setLastAssistantJson(null);
        session.getConversation().setUpdatedAt(OffsetDateTime.now());
        conversationRepository.save(session.getConversation());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.REASON, "INTENT_RESOLVED_RESET");
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.MATCHED_RESET_INTENT_CODES, resetIntentCodes);
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.STATE, session.getState());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.CONTEXT, session.getContextJson());
        audit.audit(ConvEngineAuditStage.CONVERSATION_RESET, session.getConversationId(), payload);

        return new StepResult.Continue();
    }
}
