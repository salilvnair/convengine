package com.github.salilvnair.convengine.llm.core;

import com.github.salilvnair.convengine.entity.CeLlmCallLog;
import com.github.salilvnair.convengine.repo.LlmCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Slf4j
@RequiredArgsConstructor
@Service
public class AsyncLlmCallLogPersistenceService {

    private final LlmCallLogRepository llmCallLogRepository;

    @Async
    public void saveLog(CeLlmCallLog callLog) {
        try {
            if (callLog == null) {
                return;
            }
            llmCallLogRepository.save(normalizeForPersistence(callLog));
        } catch (Exception e) {
            log.error("Failed to asynchronously persist CeLlmCallLog for conversation {}: {}",
                    callLog.getConversationId(), e.getMessage());
        }
    }

    private CeLlmCallLog normalizeForPersistence(CeLlmCallLog callLog) {
        if (callLog.getIntentCode() == null || callLog.getIntentCode().isBlank()) {
            callLog.setIntentCode("UNKNOWN");
        }
        if (callLog.getStateCode() == null || callLog.getStateCode().isBlank()) {
            callLog.setStateCode("UNKNOWN");
        }
        if (callLog.getProvider() == null || callLog.getProvider().isBlank()) {
            callLog.setProvider("UNKNOWN");
        }
        if (callLog.getModel() == null || callLog.getModel().isBlank()) {
            callLog.setModel("UNKNOWN");
        }
        if (callLog.getPromptText() == null || callLog.getPromptText().isBlank()) {
            callLog.setPromptText("[missing prompt]");
        }
        if (callLog.getUserContext() == null || callLog.getUserContext().isBlank()) {
            callLog.setUserContext("{}");
        }
        if (callLog.getSuccess() == null) {
            callLog.setSuccess(Boolean.FALSE);
        }
        if (callLog.getCreatedAt() == null) {
            callLog.setCreatedAt(OffsetDateTime.now());
        }
        return callLog;
    }
}
