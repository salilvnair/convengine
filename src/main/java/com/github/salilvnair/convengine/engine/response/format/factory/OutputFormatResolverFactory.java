package com.github.salilvnair.convengine.engine.response.format.factory;

import com.github.salilvnair.convengine.engine.response.format.core.OutputFormatResolver;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OutputFormatResolverFactory {

    private final List<OutputFormatResolver> resolvers;

    public OutputFormatResolverFactory(List<OutputFormatResolver> resolvers) {
        this.resolvers = resolvers;
    }

    public OutputFormatResolver get(String format) {
        return resolvers.stream()
                .filter(r -> r.format().equalsIgnoreCase(format))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("No OutputFormatResolver for format=" + format)
                );
    }
}
