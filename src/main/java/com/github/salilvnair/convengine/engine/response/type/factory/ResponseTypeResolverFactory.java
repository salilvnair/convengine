package com.github.salilvnair.convengine.engine.response.type.factory;

import com.github.salilvnair.convengine.engine.response.type.core.ResponseTypeResolver;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ResponseTypeResolverFactory {

    private final List<ResponseTypeResolver> resolvers;

    public ResponseTypeResolverFactory(List<ResponseTypeResolver> resolvers) {
        this.resolvers = resolvers;
    }

    public ResponseTypeResolver get(String type) {
        return resolvers.stream()
                .filter(r -> r.type().equalsIgnoreCase(type))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("No ResponseTypeResolver for type=" + type)
                );
    }
}
