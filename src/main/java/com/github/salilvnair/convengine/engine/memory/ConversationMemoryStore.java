package com.github.salilvnair.convengine.engine.memory;

import com.github.salilvnair.convengine.engine.session.EngineSession;

public interface ConversationMemoryStore {
    default String read(EngineSession session) {
        return null;
    }

    default void write(EngineSession session, String summary) {
    }
}

