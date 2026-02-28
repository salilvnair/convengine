package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.constants.ConvEngineValue;
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
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
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
@MustRunAfter({AutoAdvanceStep.class, McpToolStep.class})
@MustRunBefore(ResponseResolutionStep.class)
public class RulesStep implements EngineStep {

    private static final Logger log = LoggerFactory.getLogger(RulesStep.class);
    private final StaticConfigurationCacheService staticCacheService;
    private final RuleTypeResolverFactory typeFactory;
    private final RuleActionResolverFactory actionFactory;
    private final AuditService audit;
    private final VerboseMessagePublisher verbosePublisher;

    @Override
    public StepResult execute(EngineSession session) {
        applyRules(session, "RulesStep", RulePhase.PRE_RESPONSE_RESOLUTION.name());
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
                        ? RulePhase.POST_AGENT_INTENT.name()
                        : RulePhase.PRE_RESPONSE_RESOLUTION.name())
                : RulePhase.normalize(requestedPhase);
        boolean agentPostIntentPhase = RulePhase.POST_AGENT_INTENT.name().equals(phase);
        boolean agentPostMcpPhase = RulePhase.POST_AGENT_MCP.name().equals(phase);
        boolean toolPostExecutionPhase = RulePhase.POST_TOOL_EXECUTION.name().equals(phase);
        session.setPostIntentRule(agentPostIntentPhase);
        session.setRuleExecutionSource(source);
        session.setRuleExecutionOrigin(origin);
        session.putInputParam(ConvEngineInputParamKey.POST_INTENT_RULE, agentPostIntentPhase);
        session.putInputParam(ConvEngineInputParamKey.RULE_EXECUTION_SOURCE, source);
        session.putInputParam(ConvEngineInputParamKey.RULE_EXECUTION_ORIGIN, origin);
        session.putInputParam(ConvEngineInputParamKey.RULE_PHASE, phase);
        session.putInputParam(ConvEngineInputParamKey.RULE_AGENT_POST_INTENT, agentPostIntentPhase);
        session.putInputParam(ConvEngineInputParamKey.RULE_AGENT_POST_MCP, agentPostMcpPhase);
        session.putInputParam(ConvEngineInputParamKey.RULE_TOOL_POST_EXECUTION, toolPostExecutionPhase);

        boolean anyMatched = false;
        boolean evaluatedAnyRule = false;
        int maxPasses = 5;
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean passChanged = false;
            String passIntent = session.getIntent();
            String passState = session.getState();
            List<CeRule> allRules = staticCacheService.findEligibleRules(passIntent, passState, phase);

            for (CeRule rule : allRules) {
                evaluatedAnyRule = true;

                RuleTypeResolver typeResolver = typeFactory.get(rule.getRuleType());
                if (typeResolver == null) {
                    auditRuleNoMatch(session, source, origin, phase, passState, agentPostIntentPhase, agentPostMcpPhase,
                            toolPostExecutionPhase, rule, "TYPE_RESOLVER_MISSING");
                    continue;
                }
                if (!typeResolver.resolve(session, rule)) {
                    auditRuleNoMatch(session, source, origin, phase, passState, agentPostIntentPhase, agentPostMcpPhase,
                            toolPostExecutionPhase, rule, "TYPE_CONDITION_NOT_MET");
                    continue;
                }

                anyMatched = true;
                Map<String, Object> matchedPayload = new LinkedHashMap<>();
                matchedPayload.put(ConvEnginePayloadKey.RULE_ID, rule.getRuleId());
                matchedPayload.put(ConvEnginePayloadKey.ACTION, rule.getAction());
                matchedPayload.put(ConvEnginePayloadKey.RULE_TYPE, rule.getRuleType());
                matchedPayload.put(ConvEnginePayloadKey.RULE_STATE_CODE, rule.getStateCode());
                matchedPayload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
                matchedPayload.put(ConvEnginePayloadKey.STATE, session.getState());
                matchedPayload.put(ConvEnginePayloadKey.RULE_EXECUTION_SOURCE, source);
                matchedPayload.put(ConvEnginePayloadKey.RULE_EXECUTION_ORIGIN, origin);
                matchedPayload.put(ConvEnginePayloadKey.RULE_PHASE, phase);
                matchedPayload.put(ConvEnginePayloadKey.RULE_AGENT_POST_INTENT, agentPostIntentPhase);
                matchedPayload.put(ConvEnginePayloadKey.RULE_AGENT_POST_MCP, agentPostMcpPhase);
                matchedPayload.put(ConvEnginePayloadKey.RULE_TOOL_POST_EXECUTION, toolPostExecutionPhase);
                matchedPayload.put(ConvEnginePayloadKey.CONTEXT, session.contextDict());
                matchedPayload.put(ConvEnginePayloadKey.SCHEMA_JSON, session.schemaJson());
                audit.audit(
                        ConvEngineAuditStage.RULE_MATCH.withStage(source),
                        session.getConversationId(),
                        matchedPayload);
                verbosePublisher.publish(session, "RulesStep", "RULE_MATCH", rule.getRuleId(), null, false, matchedPayload);

                RuleActionResolver actionResolver = actionFactory.get(rule.getAction(), session);
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
                payload.put(ConvEnginePayloadKey.RULE_ID, rule.getRuleId());
                payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
                payload.put(ConvEnginePayloadKey.TYPE, rule.getRuleType());
                payload.put(ConvEnginePayloadKey.RULE_STATE_CODE, rule.getStateCode());
                payload.put(ConvEnginePayloadKey.PATTERN, rule.getMatchPattern());
                payload.put(ConvEnginePayloadKey.ACTION, rule.getAction());
                payload.put(ConvEnginePayloadKey.RULE_EXECUTION_SOURCE, source);
                payload.put(ConvEnginePayloadKey.RULE_EXECUTION_ORIGIN, origin);
                payload.put(ConvEnginePayloadKey.RULE_PHASE, phase);
                payload.put(ConvEnginePayloadKey.RULE_AGENT_POST_INTENT, agentPostIntentPhase);
                payload.put(ConvEnginePayloadKey.RULE_AGENT_POST_MCP, agentPostMcpPhase);
                payload.put(ConvEnginePayloadKey.RULE_TOOL_POST_EXECUTION, toolPostExecutionPhase);
                payload.put(ConvEnginePayloadKey.ACTION_VALUE, JsonUtil.parseOrNull(rule.getActionValue()));
                log.info("Rule applied: {}", payload);
                audit.audit(ConvEngineAuditStage.RULE_APPLIED.withStage(source), session.getConversationId(), payload);
                verbosePublisher.publish(session, "RulesStep", "RULE_APPLIED", rule.getRuleId(), null, false, payload);
            }

            if (!passChanged) {
                break;
            }
        }

        if (!anyMatched && !evaluatedAnyRule) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
            payload.put(ConvEnginePayloadKey.STATE, session.getState());
            payload.put(ConvEnginePayloadKey.RULE_EXECUTION_SOURCE, source);
            payload.put(ConvEnginePayloadKey.RULE_EXECUTION_ORIGIN, origin);
            payload.put(ConvEnginePayloadKey.RULE_PHASE, phase);
            payload.put(ConvEnginePayloadKey.RULE_AGENT_POST_INTENT, agentPostIntentPhase);
            payload.put(ConvEnginePayloadKey.RULE_AGENT_POST_MCP, agentPostMcpPhase);
            payload.put(ConvEnginePayloadKey.RULE_TOOL_POST_EXECUTION, toolPostExecutionPhase);
            audit.audit(ConvEngineAuditStage.RULE_NO_MATCH.withStage(source), session.getConversationId(), payload);
            verbosePublisher.publish(session, "RulesStep", "RULE_NO_MATCH", null, null, false, payload);
        }
    }

    private boolean matchesState(String ruleStateCode, String sessionState) {
        if (ruleStateCode == null || ruleStateCode.isBlank()) {
            return true;
        }
        if (ConvEngineValue.ANY.equalsIgnoreCase(ruleStateCode.trim())) {
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
            String evaluatedState,
            boolean agentPostIntentPhase,
            boolean agentPostMcpPhase,
            boolean toolPostExecutionPhase,
            CeRule rule,
            String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ConvEnginePayloadKey.RULE_ID, rule.getRuleId());
        payload.put(ConvEnginePayloadKey.TYPE, rule.getRuleType());
        payload.put(ConvEnginePayloadKey.RULE_STATE_CODE, rule.getStateCode());
        payload.put(ConvEnginePayloadKey.PATTERN, rule.getMatchPattern());
        payload.put(ConvEnginePayloadKey.ACTION, rule.getAction());
        payload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(ConvEnginePayloadKey.STATE, session.getState());
        payload.put(ConvEnginePayloadKey.EVALUATED_STATE, evaluatedState);
        payload.put(ConvEnginePayloadKey.REASON, reason);
        payload.put(ConvEnginePayloadKey.RULE_EXECUTION_SOURCE, source);
        payload.put(ConvEnginePayloadKey.RULE_EXECUTION_ORIGIN, origin);
        payload.put(ConvEnginePayloadKey.RULE_PHASE, phase);
        payload.put(ConvEnginePayloadKey.RULE_DB_PHASE, rule.getPhase());
        payload.put(ConvEnginePayloadKey.RULE_AGENT_POST_INTENT, agentPostIntentPhase);
        payload.put(ConvEnginePayloadKey.RULE_AGENT_POST_MCP, agentPostMcpPhase);
        payload.put(ConvEnginePayloadKey.RULE_TOOL_POST_EXECUTION, toolPostExecutionPhase);
        audit.audit(ConvEngineAuditStage.RULE_NO_MATCH.withStage(source), session.getConversationId(), payload);
    }
}
