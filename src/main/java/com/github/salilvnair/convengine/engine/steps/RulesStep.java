package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
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
import java.util.Objects;

@RequiredArgsConstructor
@Component
@MustRunAfter(AutoAdvanceStep.class)
@MustRunBefore(ResponseResolutionStep.class)
public class RulesStep implements EngineStep {

    private static final Logger log = LoggerFactory.getLogger(RulesStep.class);
    private final RuleRepository ruleRepo;
    private final RuleTypeResolverFactory typeFactory;
    private final RuleActionResolverFactory actionFactory;
    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {
        boolean anyMatched = false;
        int maxPasses = 5;

        for (int pass = 0; pass < maxPasses; pass++) {
            boolean passChanged = false;
            String passIntent = session.getIntent();

            for (CeRule rule : ruleRepo.findByEnabledTrueOrderByPriorityAsc()) {

                if (rule.getIntentCode() != null &&
                        !rule.getIntentCode().equalsIgnoreCase(passIntent)) {
                    continue;
                }

                RuleTypeResolver typeResolver = typeFactory.get(rule.getRuleType());
                if (typeResolver == null || !typeResolver.resolve(session, rule)) {
                    continue;
                }

                anyMatched = true;
                Map<String, Object> matchedPayload = new LinkedHashMap<>();
                matchedPayload.put("ruleId", rule.getRuleId());
                matchedPayload.put("action", rule.getAction());
                matchedPayload.put("ruleType", rule.getRuleType());
                matchedPayload.put("intent", session.getIntent());
                matchedPayload.put("state", session.getState());
                matchedPayload.put("context", session.contextDict());
                matchedPayload.put("extractedData", session.extractedDataDict());
                audit.audit(
                        "RULE_MATCHED",
                        session.getConversationId(),
                        matchedPayload
                );

                RuleActionResolver actionResolver = actionFactory.get(rule.getAction());
                String previousIntent = session.getIntent();
                String previousState = session.getState();

                if (actionResolver != null) {
                    actionResolver.resolve(session, rule);
                }

                if (!Objects.equals(previousIntent, session.getIntent())
                        || !Objects.equals(previousState, session.getState())) {
                    passChanged = true;
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("ruleId", rule.getRuleId());
                payload.put("intent", session.getIntent());
                payload.put("type", rule.getRuleType());
                payload.put("pattern", rule.getMatchPattern());
                payload.put("action", rule.getAction());
                payload.put("actionValue", JsonUtil.parseOrNull(rule.getActionValue()));
                log.info("Rule applied: {}", payload);
                audit.audit("RULE_APPLIED", session.getConversationId(), payload);
            }

            if (!passChanged) {
                break;
            }
        }

        if (!anyMatched) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", session.getIntent());
            payload.put("state", session.getState());
            audit.audit("RULE_NO_MATCH", session.getConversationId(), payload);
        }

        session.syncToConversation();
        return new StepResult.Continue();
    }
}
