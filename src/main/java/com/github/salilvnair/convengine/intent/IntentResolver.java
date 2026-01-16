package com.github.salilvnair.convengine.intent;

import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface IntentResolver {
    /**
     * Return resolved intent or null if not resolved.
     */
    String resolve(EngineSession session);
}
