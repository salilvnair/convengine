package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePolicy;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import com.github.salilvnair.convengine.repo.PolicyRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class PolicyEnforcementStep implements EngineStep {

    private final PolicyRepository policyRepo;
    private final ConversationRepository conversationRepo;
    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {
        String userText = session.getUserText();

        for (CePolicy policy : policyRepo.findByEnabledTrueOrderByPriorityAsc()) {
            if (matches(policy.getRuleType(), policy.getPattern(), userText)) {

                audit.audit("POLICY_BLOCK", session.getConversationId(),
                        "{\"policyId\":" + policy.getPolicyId() +
                                ",\"ruleType\":\"" + JsonUtil.escape(policy.getRuleType()) +
                                "\",\"pattern\":\"" + JsonUtil.escape(policy.getPattern()) + "\"}");

                session.getConversation().setStatus("BLOCKED");
                session.getConversation().setLastAssistantJson(jsonText(policy.getResponseText()));
                session.getConversation().setUpdatedAt(OffsetDateTime.now());
                conversationRepo.save(session.getConversation());

                EngineResult out = new EngineResult(
                        session.getIntent(),
                        session.getState(),
                        new TextPayload(policy.getResponseText()),
                        session.getContextJson()
                );
                return new StepResult.Stop(out);
            }
        }

        return new StepResult.Continue();
    }

    private boolean matches(String type, String pattern, String text) {
        if (type == null || pattern == null || text == null) return false;
        return switch (type.trim().toUpperCase()) {
            case "REGEX" -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text).find();
            case "CONTAINS" -> text.toLowerCase().contains(pattern.toLowerCase());
            case "STARTS_WITH" -> text.toLowerCase().startsWith(pattern.toLowerCase());
            default -> false;
        };
    }

    private String jsonText(String text) {
        return "{\"type\":\"TEXT\",\"value\":\"" + JsonUtil.escape(text) + "\"}";
    }
}
