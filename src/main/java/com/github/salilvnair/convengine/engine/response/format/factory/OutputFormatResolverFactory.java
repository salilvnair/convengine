package com.github.salilvnair.convengine.engine.response.format.factory;

import com.github.salilvnair.convengine.engine.response.format.core.OutputFormatResolver;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class OutputFormatResolverFactory {

    private final List<OutputFormatResolver> resolvers;
    private final VerboseMessagePublisher verbosePublisher;

    public OutputFormatResolverFactory(List<OutputFormatResolver> resolvers, VerboseMessagePublisher verbosePublisher) {
        this.resolvers = resolvers;
        this.verbosePublisher = verbosePublisher;
    }

    public OutputFormatResolver get(String format) {
        return get(format, null);
    }

    public OutputFormatResolver get(String format, EngineSession session) {
        OutputFormatResolver resolver = resolvers.stream()
                .filter(r -> r.format().equalsIgnoreCase(format))
                .findFirst()
                .orElse(null);
        if (resolver != null) {
            if (session != null) {
                verbosePublisher.publish(session, "OutputFormatResolverFactory", "OUTPUT_FORMAT_RESOLVER_SELECTED", null,
                        null, false, Map.of("outputFormat", format, "resolver", resolver.getClass().getSimpleName()));
            }
            return resolver;
        }
        if (session != null) {
            verbosePublisher.publish(session, "OutputFormatResolverFactory", "OUTPUT_FORMAT_RESOLVER_NOT_FOUND", null,
                    null, true, Map.of("outputFormat", format));
        }
        throw new IllegalStateException("No OutputFormatResolver for format=" + format);
    }
}
