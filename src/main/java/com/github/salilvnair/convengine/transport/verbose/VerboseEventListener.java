package com.github.salilvnair.convengine.transport.verbose;

import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;

import java.util.UUID;

public interface VerboseEventListener {
    void onVerbose(UUID conversationId, VerboseStreamPayload payload);
}
