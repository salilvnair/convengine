package com.github.salilvnair.convengine.engine.rule.action.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.rule.action.helper.RuleConditionEvaluator;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import com.github.salilvnair.convengine.util.JsonUtil;
import com.github.salilvnair.convengine.util.JsonPathUtil;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleTypeResolversTest {

    @Test
    void exactRuleTypeResolverMatchesIgnoringCaseAndWhitespace() {
        ExactRuleTypeResolver resolver = new ExactRuleTypeResolver();
        EngineSession session = newSession("  Go Ahead  ");
        CeRule rule = rule(" go ahead ");

        assertTrue(resolver.resolve(session, rule));
    }

    @Test
    void regexRuleTypeResolverFindsPatternIgnoringCase() {
        RegexRuleTypeResolver resolver = new RegexRuleTypeResolver();
        EngineSession session = newSession("Please process the loan");
        CeRule rule = rule("loan");

        assertTrue(resolver.resolve(session, rule));
    }

    @Test
    void jsonPathRuleTypeResolverEvaluatesAgainstSessionFacts() {
        JsonPathRuleTypeResolver resolver = new JsonPathRuleTypeResolver(new RuleConditionEvaluator(new JsonPathUtil()));
        EngineSession session = newSession("hello");
        session.setIntent("LOAN_APPLICATION");
        session.setState("CONFIRMATION");
        session.setContextJson(JsonUtil.toJson(java.util.Map.of("mcp", java.util.Map.of("finalAnswer", "done"))));
        CeRule rule = rule("$[?(@.context.mcp.finalAnswer != null && @.context.mcp.finalAnswer != '')]");

        assertTrue(resolver.resolve(session, rule));
    }

    @Test
    void jsonPathRuleTypeResolverReturnsFalseForBlankPattern() {
        JsonPathRuleTypeResolver resolver = new JsonPathRuleTypeResolver(new RuleConditionEvaluator(new JsonPathUtil()));
        EngineSession session = newSession("hello");
        CeRule rule = rule(" ");

        assertFalse(resolver.resolve(session, rule));
    }

    private EngineSession newSession(String userText) {
        return new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(userText)
                .build(), new ObjectMapper());
    }

    private CeRule rule(String pattern) {
        CeRule rule = new CeRule();
        rule.setMatchPattern(pattern);
        return rule;
    }
}
