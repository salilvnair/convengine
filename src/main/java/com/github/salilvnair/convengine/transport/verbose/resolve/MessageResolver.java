package com.github.salilvnair.convengine.transport.verbose.resolve;

import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;

import java.util.Optional;

public interface MessageResolver {
    Optional<VerboseStreamPayload> resolve(VerboseResolveRequest request);
}
