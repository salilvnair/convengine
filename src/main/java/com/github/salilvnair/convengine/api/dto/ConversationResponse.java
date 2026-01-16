package com.github.salilvnair.convengine.api.dto;

import lombok.Data;

@Data
public class ConversationResponse {

    private String conversationId;
    private String intent;
    private String state;
    private ApiPayload payload;
    private String context;
    private boolean success;
    private String errorCode;
    private boolean recoverable;
    private String message;


    public record ApiPayload(String type, Object value) {}
}