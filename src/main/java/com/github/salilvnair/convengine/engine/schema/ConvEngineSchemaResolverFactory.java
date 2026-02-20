package com.github.salilvnair.convengine.engine.schema;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ConvEngineSchemaResolverFactory {

    private final List<ConvEngineSchemaResolver> resolvers;

    public ConvEngineSchemaResolverFactory(List<ConvEngineSchemaResolver> resolvers) {
        this.resolvers = resolvers;
    }

    public ConvEngineSchemaResolver get(String schemaDefinition) {
        return resolvers.stream()
                .filter(resolver -> resolver.supports(schemaDefinition))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No ConvEngineSchemaResolver available"));
    }
}
