package com.github.salilvnair.convengine.engine.rule.action.factory;

import com.github.salilvnair.convengine.engine.rule.type.core.RuleTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuleTypeResolverFactoryTest {

    @Test
    void getReturnsResolverIgnoringCase() {
        RuleTypeResolverFactory factory = new RuleTypeResolverFactory(List.of(new StubRuleTypeResolver()));

        RuleTypeResolver resolver = factory.get("regex");

        assertNotNull(resolver);
    }

    @Test
    void getReturnsNullForUnknownOrNullType() {
        RuleTypeResolverFactory factory = new RuleTypeResolverFactory(List.of(new StubRuleTypeResolver()));

        assertNull(factory.get(null));
        assertNull(factory.get("json_path"));
    }

    private static final class StubRuleTypeResolver implements RuleTypeResolver {
        @Override
        public String type() {
            return "REGEX";
        }

        @Override
        public boolean resolve(EngineSession session, CeRule rule) {
            return false;
        }
    }
}
