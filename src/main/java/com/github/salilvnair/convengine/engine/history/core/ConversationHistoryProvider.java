package com.github.salilvnair.convengine.engine.history.core;

import com.github.salilvnair.convengine.engine.history.model.ConversationTurn;

import java.util.List;
import java.util.UUID;

public interface ConversationHistoryProvider {

    List<ConversationTurn> lastTurns(UUID conversationId, int limit);
}
