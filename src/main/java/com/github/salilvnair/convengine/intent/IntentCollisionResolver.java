package com.github.salilvnair.convengine.intent;

import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface IntentCollisionResolver {
    void resolve(EngineSession session);
}
