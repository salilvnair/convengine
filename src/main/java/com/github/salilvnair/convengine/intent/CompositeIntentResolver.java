package com.github.salilvnair.convengine.intent;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class CompositeIntentResolver implements IntentResolver {

    private final ClassifierIntentResolver classifier;
    private final AgentIntentResolver agent;


    public IntentResolutionResult resolveWithTrace(EngineSession session) {

        String classifierIntent = classifier.resolve(session);

        if (classifierIntent != null && !classifierIntent.isBlank()) {
            return new IntentResolutionResult(
                    classifierIntent,
                    IntentResolutionResult.Source.CLASSIFIER,
                    classifierIntent,
                    null
            );
        }

        String agentIntent = agent.resolve(session);

        if (agentIntent != null && !agentIntent.isBlank()) {
            return new IntentResolutionResult(
                    agentIntent,
                    IntentResolutionResult.Source.AGENT,
                    null,
                    agentIntent
            );
        }

        return null;
    }

    @Override
    public String resolve(EngineSession session) {
        IntentResolutionResult r = resolveWithTrace(session);
        return r == null ? null : r.resolvedIntent();
    }

    public record IntentResolutionResult(
            String resolvedIntent,
            Source source,
            String classifierIntent,
            String agentIntent
    ) {
        public enum Source {
            CLASSIFIER,
            AGENT
        }
    }

}
