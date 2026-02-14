package com.github.salilvnair.convengine.api.dto;

import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
public class ConversationRequest {

    private UUID conversationId;
    private String message;
    private Boolean reset;
    private Map<String, Object> inputParams;
}
