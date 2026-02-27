package com.github.salilvnair.convengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ce_conversation")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CeConversation {

    @Id
    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "status")
    private String status;

    @Column(name = "intent_code", nullable = false)
    private String intentCode;

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json")
    private String contextJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_params_json")
    private String inputParamsJson;

    @Column(name = "last_user_text")
    private String lastUserText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_assistant_json")
    private String lastAssistantJson;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

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
