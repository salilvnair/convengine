package com.github.salilvnair.convengine.engine.rule.type.factory;

import com.github.salilvnair.convengine.engine.rule.action.core.RuleActionResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RuleActionResolverFactory {

    private final Map<String, RuleActionResolver> resolvers;

    public RuleActionResolverFactory(List<RuleActionResolver> resolvers) {
        this.resolvers = resolvers.stream()
                .collect(Collectors.toMap(r -> r.action().toUpperCase(), r -> r));
    }

    public RuleActionResolver get(String action) {
        return resolvers.get(action.toUpperCase());
    }
}
