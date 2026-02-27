package com.github.salilvnair.convengine.transport.verbose.resolve;

import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class MessageResolverFactory {

    private final List<MessageResolver> resolvers;

    public Optional<VerboseStreamPayload> resolve(VerboseResolveRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        for (MessageResolver resolver : resolvers) {
            Optional<VerboseStreamPayload> out = resolver.resolve(request);
            if (out.isPresent()) {
                return out;
            }
        }
        return Optional.empty();
    }
}
