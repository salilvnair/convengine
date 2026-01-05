package com.github.salilvnair.convengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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

    private String status;
    private String intentCode;
    private String stateCode;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String contextJson;

    private String lastUserText;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String lastAssistantJson;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
