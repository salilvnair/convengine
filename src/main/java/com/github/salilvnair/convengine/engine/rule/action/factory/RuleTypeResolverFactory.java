package com.github.salilvnair.convengine.engine.rule.action.factory;

import com.github.salilvnair.convengine.engine.rule.type.core.RuleTypeResolver;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RuleTypeResolverFactory {

    private final Map<String, RuleTypeResolver> resolvers;

    public RuleTypeResolverFactory(List<RuleTypeResolver> resolvers) {
        this.resolvers = resolvers.stream()
                .collect(Collectors.toMap(r -> r.type().toUpperCase(), r -> r));
    }

    public RuleTypeResolver get(String type) {
        return resolvers.get(type.toUpperCase());
    }
}
