package com.github.salilvnair.convengine.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConversationFeedbackResponse {
    private boolean success;
    private Long feedbackId;
    private Integer capturedQueryKnowledgeCount;
    private String message;
}

