package com.github.salilvnair.convengine.engine.rule.type.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static com.github.salilvnair.convengine.support.TestConstants.EVENT_PRECHECK_STARTED;
import static com.github.salilvnair.convengine.support.TestConstants.USER_TEXT_HELLO;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RuleActionResolverFactoryTest {

    @Mock
    private VerboseMessagePublisher verboseMessagePublisher;

    private RuleActionResolverFactory factory;

    @BeforeEach
    void setUp() {
        factory = new RuleActionResolverFactory(List.of(new StubRuleActionResolver()), verboseMessagePublisher);
    }

    @Test
    void getReturnsResolverIgnoringCase() {
        RuleActionResolver resolver = factory.get("set_input_param");

        assertSame(StubRuleActionResolver.class, resolver.getClass());
    }

    @Test
    void getPublishesVerboseForMissingAction() {
        EngineSession session = newSession();

        RuleActionResolver resolver = factory.get("missing_action", session);

        assertNull(resolver);
        verify(verboseMessagePublisher).publish(
                eq(session),
                eq("RuleActionResolverFactory"),
                eq("RULE_ACTION_RESOLVER_NOT_FOUND"),
                eq(null),
                eq(null),
                eq(true),
                eq(java.util.Map.of("action", "missing_action")));
    }

    private EngineSession newSession() {
        return new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(USER_TEXT_HELLO)
                .build(), new ObjectMapper());
    }

    private static final class StubRuleActionResolver implements RuleActionResolver {
        @Override
        public String action() {
            return "SET_INPUT_PARAM";
        }

        @Override
        public void resolve(EngineSession session, CeRule rule) {
        }
    }
}
