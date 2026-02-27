package com.github.salilvnair.convengine.transport.verbose;

import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerboseEventDispatcher {

    private final List<VerboseEventListener> listeners;

    public void dispatch(UUID conversationId, VerboseStreamPayload payload) {
        if (conversationId == null || payload == null || listeners == null || listeners.isEmpty()) {
            return;
        }
        for (VerboseEventListener listener : listeners) {
            try {
                listener.onVerbose(conversationId, payload);
            } catch (Exception e) {
                log.debug("Verbose event dispatch failed listener={} convId={} msg={}",
                        listener.getClass().getSimpleName(),
                        conversationId,
                        e.getMessage());
            }
        }
    }
}
