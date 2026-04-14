package com.github.salilvnair.convengine.api.dto;

import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
public class ConversationFeedbackRequest {
    private UUID conversationId;
    private String feedbackType; // THUMBS_UP | THUMBS_DOWN
    private String messageId;
    private String assistantResponse;
    private Map<String, Object> metadata;
}

