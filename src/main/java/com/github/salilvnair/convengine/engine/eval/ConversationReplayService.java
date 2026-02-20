package com.github.salilvnair.convengine.engine.eval;

import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.core.ConversationalEngine;
import com.github.salilvnair.convengine.engine.eval.model.TraceExpectation;
import com.github.salilvnair.convengine.engine.eval.model.TraceReplayResult;
import com.github.salilvnair.convengine.engine.eval.model.TraceTurnResult;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationReplayService {

    private final ConversationalEngine engine;

    public TraceReplayResult replay(List<String> userMessages) {
        return replay(userMessages, List.of());
    }

    public TraceReplayResult replay(List<String> userMessages, List<TraceExpectation> expectations) {
        String conversationId = UUID.randomUUID().toString();
        List<TraceTurnResult> turns = new ArrayList<>();
        for (int i = 0; i < userMessages.size(); i++) {
            EngineContext context = EngineContext.builder()
                    .conversationId(conversationId)
                    .userText(userMessages.get(i))
                    .inputParams(Map.of())
                    .userInputParams(Map.of())
                    .build();
            EngineResult result = engine.process(context);
            turns.add(new TraceTurnResult(i, userMessages.get(i), result.intent(), result.state(), result.contextJson()));
        }
        List<String> failures = assertExpectations(turns, expectations);
        return new TraceReplayResult(turns, failures);
    }

    private List<String> assertExpectations(List<TraceTurnResult> turns, List<TraceExpectation> expectations) {
        List<String> failures = new ArrayList<>();
        for (TraceExpectation expectation : expectations) {
            if (expectation.turnIndex() < 0 || expectation.turnIndex() >= turns.size()) {
                failures.add("turn " + expectation.turnIndex() + " is out of range");
                continue;
            }
            TraceTurnResult turn = turns.get(expectation.turnIndex());
            if (expectation.expectedIntent() != null
                    && !expectation.expectedIntent().equalsIgnoreCase(turn.intent())) {
                failures.add("turn " + expectation.turnIndex() + " intent expected=" + expectation.expectedIntent() + " actual=" + turn.intent());
            }
            if (expectation.expectedState() != null
                    && !expectation.expectedState().equalsIgnoreCase(turn.state())) {
                failures.add("turn " + expectation.turnIndex() + " state expected=" + expectation.expectedState() + " actual=" + turn.state());
            }
        }
        return failures;
    }
}

