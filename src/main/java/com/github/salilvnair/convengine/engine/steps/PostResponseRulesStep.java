package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.core.step.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.RulePhase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(ResponseResolutionStep.class)
@MustRunBefore(MemoryStep.class)
public class PostResponseRulesStep implements EngineStep {

    private final RulesStep rulesStep;

    @Override
    public StepResult execute(EngineSession session) {
        rulesStep.applyRules(session, "PostResponseRulesStep", RulePhase.POST_RESPONSE_RESOLUTION.name());
        return new StepResult.Continue();
    }
}
