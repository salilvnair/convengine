package com.github.salilvnair.convengine.llm.context;

import java.util.UUID;

public final class LlmInvocationContext {

    private static final ThreadLocal<LlmInvocationContext> HOLDER =
            new ThreadLocal<>();

    private final UUID conversationId;
    private final String intent;
    private final String state;

    private LlmInvocationContext(
            UUID conversationId,
            String intent,
            String state
    ) {
        this.conversationId = conversationId;
        this.intent = intent;
        this.state = state;
    }

    public static void set(
            UUID conversationId,
            String intent,
            String state
    ) {
        HOLDER.set(new LlmInvocationContext(conversationId, intent, state));
    }

    public static LlmInvocationContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public UUID conversationId() {
        return conversationId;
    }

    public String intent() {
        return intent;
    }

    public String state() {
        return state;
    }
}
