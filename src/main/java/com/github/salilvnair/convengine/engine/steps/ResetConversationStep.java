package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
@Component
@MustRunAfter(LoadOrCreateConversationStep.class)
@MustRunBefore(PersistConversationBootstrapStep.class)
public class ResetConversationStep implements EngineStep {

    private static final Set<String> RESET_COMMANDS = Set.of(
            "reset",
            "restart",
            "/reset",
            "/restart"
    );

    private final AuditService audit;
    private final ConversationRepository conversationRepository;

    @Override
    public StepResult execute(EngineSession session) {
        if (!shouldReset(session)) {
            return new StepResult.Continue();
        }

        String reason = resetReason(session);
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
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.REASON, reason);
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.STATE, session.getState());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.CONTEXT, session.getContextJson());
        audit.audit(ConvEngineAuditStage.CONVERSATION_RESET, session.getConversationId(), payload);

        return new StepResult.Continue();
    }

    private boolean shouldReset(EngineSession session) {
        if (truthy(session.getInputParams().get("reset"))
                || truthy(session.getInputParams().get("restart"))
                || truthy(session.getInputParams().get("conversation_reset"))) {
            return true;
        }
        String message = session.getUserText();
        if (message == null) {
            return false;
        }
        String normalized = message.trim().toLowerCase();
        return RESET_COMMANDS.contains(normalized);
    }

    private String resetReason(EngineSession session) {
        if (truthy(session.getInputParams().get("reset"))) return "INPUT_PARAM:reset";
        if (truthy(session.getInputParams().get("restart"))) return "INPUT_PARAM:restart";
        if (truthy(session.getInputParams().get("conversation_reset"))) return "INPUT_PARAM:conversation_reset";
        return "USER_TEXT_COMMAND";
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String normalized = s.trim().toLowerCase();
            return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
        }
        if (value instanceof Number n) {
            return n.intValue() == 1;
        }
        return false;
    }
}
