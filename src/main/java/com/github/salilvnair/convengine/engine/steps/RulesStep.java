package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import com.github.salilvnair.convengine.engine.rule.action.factory.RuleTypeResolverFactory;
import com.github.salilvnair.convengine.engine.rule.type.core.RuleTypeResolver;
import com.github.salilvnair.convengine.engine.rule.type.factory.RuleActionResolverFactory;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.RulePhase;
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
        applyRules(session, "RulesStep", RulePhase.PIPELINE_RULES.name());
        session.syncToConversation();
        return new StepResult.Continue();
    }

    public void applyRules(EngineSession session, String stage) {
        applyRules(session, stage, null);
    }

    public void applyRules(EngineSession session, String stage, String requestedPhase) {
        String source = (stage == null || stage.isBlank()) ? "RulesStep" : stage;
        String origin = source.toLowerCase().contains("agentintentresolver")
                ? "AGENT_INTENT_RESOLVER"
                : "RULES_STEP";
        String phase = requestedPhase == null || requestedPhase.isBlank()
                ? ("AGENT_INTENT_RESOLVER".equals(origin)
                    ? RulePhase.AGENT_POST_INTENT.name()
                    : RulePhase.PIPELINE_RULES.name())
                : RulePhase.normalize(requestedPhase);
        boolean agentPostIntentPhase = RulePhase.AGENT_POST_INTENT.name().equals(phase);
        session.setPostIntentRule(agentPostIntentPhase);
        session.setRuleExecutionSource(source);
        session.setRuleExecutionOrigin(origin);
        session.putInputParam(ConvEngineInputParamKey.POST_INTENT_RULE, agentPostIntentPhase);
        session.putInputParam(ConvEngineInputParamKey.RULE_EXECUTION_SOURCE, source);
        session.putInputParam(ConvEngineInputParamKey.RULE_EXECUTION_ORIGIN, origin);
        session.putInputParam(ConvEngineInputParamKey.RULE_PHASE, phase);
        session.putInputParam(ConvEngineInputParamKey.RULE_AGENT_POST_INTENT, agentPostIntentPhase);

        boolean anyMatched = false;
        int maxPasses = 5;
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean passChanged = false;
            String passIntent = session.getIntent();
            String passState = session.getState();
            List<CeRule> allRules = ruleRepo.findEligibleByPhaseAndStateOrderByPriorityAsc(phase, passState);

            for (CeRule rule : allRules) {

                if (rule.getIntentCode() != null &&
                        !rule.getIntentCode().equalsIgnoreCase(passIntent)) {
                    auditRuleNoMatch(session, source, origin, phase, agentPostIntentPhase, rule, "INTENT_MISMATCH");
                    continue;
                }
                if (!matchesState(rule.getStateCode(), passState)) {
                    auditRuleNoMatch(session, source, origin, phase, agentPostIntentPhase, rule, "STATE_MISMATCH");
                    continue;
                }

                RuleTypeResolver typeResolver = typeFactory.get(rule.getRuleType());
                if (typeResolver == null) {
                    auditRuleNoMatch(session, source, origin, phase, agentPostIntentPhase, rule, "TYPE_RESOLVER_MISSING");
                    continue;
                }
                if (!typeResolver.resolve(session, rule)) {
                    auditRuleNoMatch(session, source, origin, phase, agentPostIntentPhase, rule, "TYPE_CONDITION_NOT_MET");
                    continue;
                }

                anyMatched = true;
                Map<String, Object> matchedPayload = new LinkedHashMap<>();
                matchedPayload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_ID, rule.getRuleId());
                matchedPayload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.ACTION, rule.getAction());
                matchedPayload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_TYPE, rule.getRuleType());
                matchedPayload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_STATE_CODE, rule.getStateCode());
                matchedPayload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.INTENT, session.getIntent());
                matchedPayload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.STATE, session.getState());
                matchedPayload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_EXECUTION_SOURCE, source);
                matchedPayload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_EXECUTION_ORIGIN, origin);
                matchedPayload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_PHASE, phase);
                matchedPayload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_AGENT_POST_INTENT, agentPostIntentPhase);
                matchedPayload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.CONTEXT, session.contextDict());
                matchedPayload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.SCHEMA_JSON, session.schemaJson());
                audit.audit(
                        ConvEngineAuditStage.RULE_MATCHED.withStage(source),
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
                payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_ID, rule.getRuleId());
                payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.INTENT, session.getIntent());
                payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.TYPE, rule.getRuleType());
                payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_STATE_CODE, rule.getStateCode());
                payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.PATTERN, rule.getMatchPattern());
                payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.ACTION, rule.getAction());
                payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_EXECUTION_SOURCE, source);
                payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_EXECUTION_ORIGIN, origin);
                payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_PHASE, phase);
                payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_AGENT_POST_INTENT, agentPostIntentPhase);
                payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.ACTION_VALUE, JsonUtil.parseOrNull(rule.getActionValue()));
                log.info("Rule applied: {}", payload);
                audit.audit(ConvEngineAuditStage.RULE_APPLIED.withStage(source), session.getConversationId(), payload);
            }

            if (!passChanged) {
                break;
            }
        }

        if (!anyMatched) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.INTENT, session.getIntent());
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.STATE, session.getState());
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_EXECUTION_SOURCE, source);
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_EXECUTION_ORIGIN, origin);
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_PHASE, phase);
            payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_AGENT_POST_INTENT, agentPostIntentPhase);
            audit.audit(ConvEngineAuditStage.RULE_NO_MATCH.withStage(source), session.getConversationId(), payload);
        }
    }

    private boolean matchesState(String ruleStateCode, String sessionState) {
        if (ruleStateCode == null || ruleStateCode.isBlank()) {
            return true;
        }
        if ("ANY".equalsIgnoreCase(ruleStateCode.trim())) {
            return true;
        }
        if (sessionState == null || sessionState.isBlank()) {
            return false;
        }
        return ruleStateCode.trim().equalsIgnoreCase(sessionState.trim());
    }

    private void auditRuleNoMatch(
            EngineSession session,
            String source,
            String origin,
            String phase,
            boolean agentPostIntentPhase,
            CeRule rule,
            String reason
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_ID, rule.getRuleId());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.TYPE, rule.getRuleType());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_STATE_CODE, rule.getStateCode());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.PATTERN, rule.getMatchPattern());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.ACTION, rule.getAction());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.STATE, session.getState());
        payload.put("reason", reason);
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_EXECUTION_SOURCE, source);
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_EXECUTION_ORIGIN, origin);
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_PHASE, phase);
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_AGENT_POST_INTENT, agentPostIntentPhase);
        audit.audit(ConvEngineAuditStage.RULE_NO_MATCH.withStage(source), session.getConversationId(), payload);
    }
}
