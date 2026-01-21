package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import com.github.salilvnair.convengine.engine.rule.action.factory.RuleTypeResolverFactory;
import com.github.salilvnair.convengine.engine.rule.type.core.RuleTypeResolver;
import com.github.salilvnair.convengine.engine.rule.type.factory.RuleActionResolverFactory;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import com.github.salilvnair.convengine.repo.RuleRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class RulesStep implements EngineStep {

    private static final Logger log = LoggerFactory.getLogger(RulesStep.class);
    private final RuleRepository ruleRepo;
    private final RuleTypeResolverFactory typeFactory;
    private final RuleActionResolverFactory actionFactory;
    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {

        for (CeRule rule : ruleRepo.findByEnabledTrueOrderByPriorityAsc()) {

            if (rule.getIntentCode() != null &&
                    !rule.getIntentCode().equalsIgnoreCase(session.getIntent())) {
                audit.audit(
                        "RULE_SKIPPED_INTENT_MISMATCH",
                        session.getConversationId(),
                        "{\"ruleId\":" + rule.getRuleId() +
                                ",\"expectedIntent\":\"" + JsonUtil.escape(rule.getIntentCode()) +
                                "\",\"actualIntent\":\"" + JsonUtil.escape(session.getIntent()) + "\"}"
                );
                continue;
            }

            RuleTypeResolver typeResolver = typeFactory.get(rule.getRuleType());

            if (typeResolver == null || !typeResolver.resolve(session, rule)) {

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("ruleId", rule.getRuleId());
                payload.put("intent", session.getIntent());
                payload.put("type", rule.getRuleType());
                payload.put("pattern", rule.getMatchPattern());
                payload.put("action", rule.getAction());
                payload.put("actionValue", JsonUtil.parseOrNull(rule.getActionValue()));
                log.info("Rule not applied: {}", payload);
                audit.audit(
                        typeResolver == null  ? "RULE_ACTION_MISSING" : "RULE_NOT_APPLIED",
                        session.getConversationId(),
                        JsonUtil.toJson(payload)
                );

                continue;
            }

            RuleActionResolver actionResolver = actionFactory.get(rule.getAction());

            if (actionResolver != null) {
                actionResolver.resolve(session, rule);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ruleId", rule.getRuleId());
            payload.put("intent", session.getIntent());
            payload.put("type", rule.getRuleType());
            payload.put("pattern", rule.getMatchPattern());
            payload.put("action", rule.getAction());
            payload.put("actionValue", JsonUtil.parseOrNull(rule.getActionValue()));
            log.info("Rule applied: {}", payload);
            audit.audit(
                    "RULE_APPLIED",
                    session.getConversationId(),
                    JsonUtil.toJson(payload)
            );
        }

        session.syncToConversation();
        return new StepResult.Continue();
    }
}
