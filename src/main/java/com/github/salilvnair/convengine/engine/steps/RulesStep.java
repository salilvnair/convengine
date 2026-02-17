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
import java.util.List;
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
        applyRules(session, "RulesStep");
        session.syncToConversation();
        return new StepResult.Continue();
    }

    public void applyRules(EngineSession session, String stage) {
        String source = (stage == null || stage.isBlank()) ? "RulesStep" : stage;
        String origin = source.toLowerCase().contains("agentintentresolver")
                ? "AGENT_INTENT_RESOLVER"
                : "RULES_STEP";
        boolean agentPostIntentPhase = "AGENT_INTENT_RESOLVER".equals(origin);
        String phase = agentPostIntentPhase ? "AGENT_POST_INTENT" : "PIPELINE_RULES";
        session.setPostIntentRule(agentPostIntentPhase);
        session.setRuleExecutionSource(source);
        session.setRuleExecutionOrigin(origin);
        session.putInputParam("post_intent_rule", agentPostIntentPhase);
        session.putInputParam("rule_execution_source", source);
        session.putInputParam("rule_execution_origin", origin);
        session.putInputParam("rule_phase", phase);
        session.putInputParam("rule_agent_post_intent", agentPostIntentPhase);

        boolean anyMatched = false;
        int maxPasses = 5;
        List<CeRule> allRules = ruleRepo.findByEnabledTrueOrderByPriorityAsc();
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean passChanged = false;
            String passIntent = session.getIntent();

            for (CeRule rule : allRules) {

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
                matchedPayload.put("ruleExecutionSource", source);
                matchedPayload.put("ruleExecutionOrigin", origin);
                matchedPayload.put("rulePhase", phase);
                matchedPayload.put("ruleAgentPostIntent", agentPostIntentPhase);
                matchedPayload.put("context", session.contextDict());
                matchedPayload.put("schemaJson", session.schemaJson());
                audit.audit(
                        "RULE_MATCHED (" + stage + ")",
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
                payload.put("ruleExecutionSource", source);
                payload.put("ruleExecutionOrigin", origin);
                payload.put("rulePhase", phase);
                payload.put("ruleAgentPostIntent", agentPostIntentPhase);
                payload.put("actionValue", JsonUtil.parseOrNull(rule.getActionValue()));
                log.info("Rule applied: {}", payload);
                audit.audit("RULE_APPLIED ("+stage+")" , session.getConversationId(), payload);
            }

            if (!passChanged) {
                break;
            }
        }

        if (!anyMatched) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", session.getIntent());
            payload.put("state", session.getState());
            payload.put("ruleExecutionSource", source);
            payload.put("ruleExecutionOrigin", origin);
            payload.put("rulePhase", phase);
            payload.put("ruleAgentPostIntent", agentPostIntentPhase);
            audit.audit("RULE_NO_MATCH ("+stage+")", session.getConversationId(), payload);
        }
    }
}
