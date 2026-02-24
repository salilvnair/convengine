package com.github.salilvnair.convengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ce_conversation_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CeConversationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @Column(nullable = false, name = "conversation_id")
    private UUID conversationId;

    @Column(nullable = false, name = "user_input")
    private String userInput;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assistant_output")
    private String assistantOutput;

    @Column(nullable = false, name = "created_at")
    private OffsetDateTime createdAt;

    @Column(nullable = false, name = "modified_at")
    private OffsetDateTime modifiedAt;
}
