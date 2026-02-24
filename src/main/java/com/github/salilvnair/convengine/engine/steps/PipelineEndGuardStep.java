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
import com.github.salilvnair.convengine.entity.CeConversationHistory;
import com.github.salilvnair.convengine.service.AsyncConversationHistoryPersistenceService;
import com.github.salilvnair.convengine.service.ConversationHistoryCacheService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.time.OffsetDateTime;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@TerminalStep
public class PipelineEndGuardStep implements EngineStep {

        private static final Logger log = LoggerFactory.getLogger(PipelineEndGuardStep.class);

        private final AuditService audit;
        private final AsyncConversationHistoryPersistenceService historyPersistenceService;
        private final ConversationHistoryCacheService historyCacheService;

        @Override
        public StepResult execute(EngineSession session) {

                // Sort by start time just in case
                session.getStepTimings().sort(Comparator.comparingLong(StepTiming::getStartedAtNs));

                long totalMs = session.getStepTimings().stream().mapToLong(StepTiming::getDurationMs).sum();

                // Log in app logs
                String timingLine = session.getStepTimings().stream()
                                .map(t -> t.getStepName() + "=" + t.getDurationMs() + "ms"
                                                + (t.isSuccess() ? "" : "(ERR)"))
                                .reduce((a, b) -> a + ", " + b)
                                .orElse("");

                log.info("ConvEngine timings convId={} total={}ms [{}]",
                                session.getConversationId(), totalMs, timingLine);

                // Optional audit row (single compact record)
                String payload = "{\"totalMs\":" + totalMs +
                                ",\"steps\":\"" + JsonUtil.escape(timingLine) + "\"}";

                audit.audit(ConvEngineAuditStage.PIPELINE_TIMING, session.getConversationId(), payload);

                saveConversationHistory(session);

                return new StepResult.Continue();
        }

        private void saveConversationHistory(EngineSession session) {
                String userInput = session.getUserText();
                String assistantOutput = session.getConversation() != null
                                ? session.getConversation().getLastAssistantJson()
                                : null;
                if (assistantOutput == null || assistantOutput.isBlank()) {
                        assistantOutput = session.getLastLlmOutput();
                }

                if (userInput != null && !userInput.isBlank() && assistantOutput != null
                                && !assistantOutput.isBlank()) {
                        CeConversationHistory history = CeConversationHistory.builder()
                                        .conversationId(session.getConversationId())
                                        .userInput(userInput)
                                        .assistantOutput(assistantOutput)
                                        .createdAt(OffsetDateTime.now())
                                        .modifiedAt(OffsetDateTime.now())
                                        .build();
                        historyPersistenceService.saveHistory(history);

                        List<CeConversationHistory> cachedHistory = new ArrayList<>(
                                        historyCacheService.getHistory(session.getConversationId()));
                        cachedHistory.add(0, history);
                        historyCacheService.updateHistoryCache(session.getConversationId(), cachedHistory);
                }
        }
}
