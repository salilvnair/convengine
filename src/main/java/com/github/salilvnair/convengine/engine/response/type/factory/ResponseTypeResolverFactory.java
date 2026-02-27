package com.github.salilvnair.convengine.engine.response.type.factory;

import com.github.salilvnair.convengine.engine.response.type.core.ResponseTypeResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ResponseTypeResolverFactory {

    private final List<ResponseTypeResolver> resolvers;
    private final VerboseMessagePublisher verbosePublisher;

    public ResponseTypeResolverFactory(List<ResponseTypeResolver> resolvers, VerboseMessagePublisher verbosePublisher) {
        this.resolvers = resolvers;
        this.verbosePublisher = verbosePublisher;
    }

    public ResponseTypeResolver get(String type) {
        return get(type, null);
    }

    public ResponseTypeResolver get(String type, EngineSession session) {
        ResponseTypeResolver resolver = resolvers.stream()
                .filter(r -> r.type().equalsIgnoreCase(type))
                .findFirst()
                .orElse(null);
        if (resolver != null) {
            if (session != null) {
                verbosePublisher.publish(session, "ResponseTypeResolverFactory", "RESPONSE_TYPE_RESOLVER_SELECTED", null,
                        null, false, Map.of("responseType", type, "resolver", resolver.getClass().getSimpleName()));
            }
            return resolver;
        }
        if (session != null) {
            verbosePublisher.publish(session, "ResponseTypeResolverFactory", "RESPONSE_TYPE_RESOLVER_NOT_FOUND", null,
                    null, true, Map.of("responseType", type));
        }
        throw new IllegalStateException("No ResponseTypeResolver for type=" + type);
    }
}
