package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.model.StepTiming;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.pipeline.annotation.TerminalStep;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@TerminalStep
public class PipelineEndGuardStep implements EngineStep {

    private static final Logger log = LoggerFactory.getLogger(PipelineEndGuardStep.class);

    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {

        // Sort by start time just in case
        session.getStepTimings().sort(Comparator.comparingLong(StepTiming::getStartedAtNs));

        long totalMs = session.getStepTimings().stream().mapToLong(StepTiming::getDurationMs).sum();

        // Log in app logs
        String timingLine = session.getStepTimings().stream()
                .map(t -> t.getStepName() + "=" + t.getDurationMs() + "ms" + (t.isSuccess() ? "" : "(ERR)"))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        log.info("ConvEngine timings convId={} total={}ms [{}]",
                session.getConversationId(), totalMs, timingLine);

        // Optional audit row (single compact record)
        String payload = "{\"totalMs\":" + totalMs +
                ",\"steps\":\"" + JsonUtil.escape(timingLine) + "\"}";

        audit.audit(ConvEngineAuditStage.PIPELINE_TIMING, session.getConversationId(), payload);

        return new StepResult.Continue();
    }
}
