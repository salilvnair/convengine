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

    // Kept nullable at the JPA metadata level so Hibernate's pre-save
    // not-null check doesn't fire ahead of @PrePersist. The column is still
    // NOT NULL in the DB schema; @PrePersist below mints a synthetic UUID
    // for debug/tooling callers that have no conversation context.
    @Column(name = "conversation_id")
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
        // Debug / tooling paths (e.g. SemanticDebugController) fire LLM calls
        // outside any conversation, so session.getConversationId() is null.
        // Mint a synthetic UUID here so the not-null column still satisfies
        // DB constraints without forcing every caller to fabricate one.
        if (conversationId == null) {
            conversationId = UUID.randomUUID();
        }
        if (intentCode == null || intentCode.isBlank()) {
            intentCode = "UNKNOWN";
        }
        if (stateCode == null || stateCode.isBlank()) {
            stateCode = "UNKNOWN";
        }
        if (provider == null || provider.isBlank()) {
            provider = "UNKNOWN";
        }
        if (model == null || model.isBlank()) {
            model = "UNKNOWN";
        }
        if (promptText == null) {
            promptText = "";
        }
        if (userContext == null) {
            userContext = "{}";
        }
        if (success == null) {
            success = Boolean.FALSE;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
