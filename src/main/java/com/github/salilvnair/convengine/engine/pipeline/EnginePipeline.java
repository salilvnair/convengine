package com.github.salilvnair.convengine.engine.pipeline;

import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import java.util.List;

public final class EnginePipeline {

    private final List<EngineStep> steps;

    public EnginePipeline(List<EngineStep> steps) {
        this.steps = steps;
    }

    public EngineResult execute(EngineSession session) {
        for (EngineStep step : steps) {
            StepResult r = step.execute(session);
            if (r instanceof StepResult.Stop(EngineResult result)) {
                return result;
            }
        }
        // ResponseResolutionStep must have set finalResult
        if (session.getFinalResult() == null) {
            throw new ConversationEngineException(
                    ConversationEngineErrorCode.PIPELINE_NO_FINAL_RESULT
            );
        }
        return session.getFinalResult();
    }
}
