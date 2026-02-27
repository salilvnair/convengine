package com.github.salilvnair.convengine.engine.rule.type.factory;

import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RuleActionResolverFactory {

    private final Map<String, RuleActionResolver> resolvers;
    private final VerboseMessagePublisher verbosePublisher;

    public RuleActionResolverFactory(List<RuleActionResolver> resolvers, VerboseMessagePublisher verbosePublisher) {
        this.resolvers = resolvers.stream()
                .collect(Collectors.toMap(r -> r.action().toUpperCase(), r -> r));
        this.verbosePublisher = verbosePublisher;
    }

    public RuleActionResolver get(String action) {
        return get(action, null);
    }

    public RuleActionResolver get(String action, EngineSession session) {
        if (action == null) {
            if (session != null) {
                verbosePublisher.publish(session, "RuleActionResolverFactory", "RULE_ACTION_RESOLVER_NOT_FOUND", null,
                        null, true, Map.of("reason", "null_action"));
            }
            return null;
        }
        RuleActionResolver resolver = resolvers.get(action.toUpperCase());
        if (session != null) {
            if (resolver != null) {
                verbosePublisher.publish(session, "RuleActionResolverFactory", "RULE_ACTION_RESOLVER_SELECTED", null,
                        null, false, Map.of("action", action, "resolver", resolver.getClass().getSimpleName()));
            } else {
                verbosePublisher.publish(session, "RuleActionResolverFactory", "RULE_ACTION_RESOLVER_NOT_FOUND", null,
                        null, true, Map.of("action", action));
            }
        }
        return resolver;
    }
}
