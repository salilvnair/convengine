package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ce_llm_call_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CeLlmCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "llm_call_id")
    private Long llmCallId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "intent_code", nullable = false)
    private String intentCode;

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "prompt_text", nullable = false)
    private String promptText;

    @Column(name = "user_context", nullable = false)
    private String userContext;

    @Column(name = "response_text")
    private String responseText;

    @Column(name = "success", nullable = false)
    private Boolean success;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    @PreUpdate
    private void ensureIntentAndState() {
        if (intentCode == null || intentCode.isBlank()) {
            intentCode = "UNKNOWN";
        }
        if (stateCode == null || stateCode.isBlank()) {
            stateCode = "UNKNOWN";
        }
    }
}
