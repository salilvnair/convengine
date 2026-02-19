package com.github.salilvnair.convengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

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

    @Column(name = "intent_code")
    private String intentCode;

    @Column(name = "state_code")
    private String stateCode;

    @Column(name = "context_json")
    private String contextJson;

    @Column(name = "input_params_json")
    private String inputParamsJson;

    @Column(name = "last_user_text")
    private String lastUserText;

    @Column(name = "last_assistant_json")
    private String lastAssistantJson;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
