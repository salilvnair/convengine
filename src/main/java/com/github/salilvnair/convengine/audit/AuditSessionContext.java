package com.github.salilvnair.convengine.audit;

import com.github.salilvnair.convengine.engine.session.EngineSession;

public final class AuditSessionContext {

    private static final ThreadLocal<EngineSession> SESSION_HOLDER = new ThreadLocal<>();

    private AuditSessionContext() {
    }

    public static void set(EngineSession session) {
        SESSION_HOLDER.set(session);
    }

    public static EngineSession get() {
        return SESSION_HOLDER.get();
    }

    public static void clear() {
        SESSION_HOLDER.remove();
    }
}

