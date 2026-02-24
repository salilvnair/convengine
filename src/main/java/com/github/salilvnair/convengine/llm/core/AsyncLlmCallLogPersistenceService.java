package com.github.salilvnair.convengine.llm.core;

import com.github.salilvnair.convengine.entity.CeLlmCallLog;
import com.github.salilvnair.convengine.repo.LlmCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class AsyncLlmCallLogPersistenceService {

    private final LlmCallLogRepository llmCallLogRepository;

    @Async
    public void saveLog(CeLlmCallLog callLog) {
        try {
            llmCallLogRepository.save(callLog);
        } catch (Exception e) {
            log.error("Failed to asynchronously persist CeLlmCallLog for conversation {}: {}",
                    callLog.getConversationId(), e.getMessage());
        }
    }
}
