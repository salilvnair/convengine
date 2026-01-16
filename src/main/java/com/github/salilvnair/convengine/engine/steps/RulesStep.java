package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.RuleAction;
import com.github.salilvnair.convengine.entity.CeRule;
import com.github.salilvnair.convengine.model.TextPayload;
import com.github.salilvnair.convengine.repo.ConversationRepository;
import com.github.salilvnair.convengine.repo.RuleRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class RulesStep implements EngineStep {

    private final RuleRepository ruleRepo;
    private final ConversationRepository conversationRepo;
    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {
        return applyRules(session, session.getUserText());
    }

    public StepResult applyRules(EngineSession session, String text) {

        String intent = session.getIntent();
        String state = session.getState();

        for (CeRule rule : ruleRepo.findByEnabledTrueOrderByPriorityAsc()) {

            if (!matches(rule.getRuleType(), rule.getMatchPattern(), text)) {
                continue;
            }

            if (rule.getIntentCode() != null && (!rule.getIntentCode().equalsIgnoreCase(intent))) {
                continue;
            }

            RuleAction action = RuleAction.valueOf(rule.getAction().trim().toUpperCase());

            audit.audit("RULE_MATCHED", session.getConversationId(),
                    "{\"ruleId\":" + rule.getRuleId() +
                            ",\"action\":\"" + JsonUtil.escape(action.name()) + "\"}");

            switch (action) {
                case SET_INTENT -> {
                    intent = rule.getActionValue();
                    session.setIntent(intent);
                    session.getConversation().setIntentCode(intent);
                    audit.audit("SET_INTENT", session.getConversationId(),
                            "{\"ce_rule_id\":" + rule.getRuleId() +
                                    ",\"intent\":\"" + JsonUtil.escape(intent) +
                                    "\",\"state\":\"" + JsonUtil.escape(state) + "\"}");
                }
                case SET_STATE -> {
                    state = rule.getActionValue();
                    session.setState(state);
                    session.getConversation().setStateCode(state);
                    audit.audit("SET_STATE", session.getConversationId(),
                            "{\"ce_rule_id\":" + rule.getRuleId() +
                                    ",\"state\":\"" + JsonUtil.escape(state) +
                                    "\",\"intent\":\"" + JsonUtil.escape(intent) + "\"}");
                }
                case SHORT_CIRCUIT -> {
                    session.getConversation().setLastAssistantJson(jsonText(rule.getActionValue()));
                    session.getConversation().setUpdatedAt(OffsetDateTime.now());
                    conversationRepo.save(session.getConversation());
                    audit.audit("SHORT_CIRCUIT", session.getConversationId(),
                            "{\"ce_rule_id\":" + rule.getRuleId() +
                                    ",\"message\":\"" + JsonUtil.escape(rule.getActionValue()) +
                                    "\",\"intent\":\"" + JsonUtil.escape(intent) +
                                    "\",\"state\":\"" + JsonUtil.escape(state) + "\"}");

                    EngineResult out = new EngineResult(intent, state, new TextPayload(rule.getActionValue()), session.getContextJson());
                    return new StepResult.Stop(out);
                }
                case RESOLVE_INTENT -> {
                    session.setIntent(null);
                    audit.audit("RESOLVE_INTENT_TRIGGERED", session.getConversationId(),
                            "{\"ce_rule_id\":" + rule.getRuleId() + "}");
                }
            }
        }

        session.setIntent(intent);
        session.setState(state);
        session.getConversation().setIntentCode(intent);
        session.getConversation().setStateCode(state);

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
