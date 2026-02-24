package com.github.salilvnair.convengine.intent;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class CompositeIntentResolver implements IntentResolver {

    private final ClassifierIntentResolver classifier;
    private final AgentIntentResolver agentIntentResolver;


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

        String agentIntent = agentIntentResolver.resolve(session);

        if (agentIntent != null && !agentIntent.isBlank()) {
            return new IntentResolutionResult(
                    agentIntent,
                    IntentResolutionResult.Source.AGENT,
                    classifierIntent,
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
