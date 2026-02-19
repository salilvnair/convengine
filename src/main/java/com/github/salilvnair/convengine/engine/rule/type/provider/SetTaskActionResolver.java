package com.github.salilvnair.convengine.engine.rule.type.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import com.github.salilvnair.convengine.engine.rule.task.CeRuleTaskExecutor;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.RuleAction;
import com.github.salilvnair.convengine.entity.CeRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class SetTaskActionResolver implements RuleActionResolver {
    private final AuditService audit;
    private final CeRuleTaskExecutor ceRuleTaskExecutor;

    @Override
    public String action() {
        return RuleAction.SET_TASK.name();
    }

    @Override
    public void resolve(EngineSession session, CeRule rule) {
        String[] beanTaskMetaData = parseRuleTasks(rule.getActionValue());
        ceRuleTaskExecutor.execute(beanTaskMetaData[0], beanTaskMetaData[1], session, rule);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.RULE_ID, rule.getRuleId());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.BEAN_NAME, beanTaskMetaData[0]);
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.BEAN_METHOD_NAMES, beanTaskMetaData[1]);
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.INTENT, session.getIntent());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.STATE, session.getState());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.CONTEXT, session.contextDict());
        payload.put(com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey.INPUT_PARAMS, session.safeInputParams());
        audit.audit(RuleAction.SET_TASK.name(), session.getConversationId(), payload);
    }

    private String[] parseRuleTasks(String actionValue) {
        String raw = actionValue == null ? "" : actionValue.trim();
        if (raw.isBlank()) {
            throw new IllegalArgumentException("Action value for SET_TASK cannot be blank");
        }
        if (raw.contains(":")) {
            String[] parts = raw.split(":", 2);
            String beanName = parts[0] == null || parts[0].isBlank() ? "llm_json" : parts[0].trim();
            String beanMethodNames = parts.length > 1 ? parts[1].trim() : "";
            if (beanName.isEmpty() || beanMethodNames.isEmpty()) {
                throw new IllegalArgumentException("Both bean name and method name(s) must be provided for SET_TASK action");
            }
            return new String[]{beanName, beanMethodNames};
        }
        return new String[]{raw, raw};
    }
}
