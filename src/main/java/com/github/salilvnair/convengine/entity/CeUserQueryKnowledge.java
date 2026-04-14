package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ce_user_query_knowledge")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CeUserQueryKnowledge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "feedback_id")
    private Long feedbackId;

    @Column(name = "feedback_type", length = 32)
    private String feedbackType;

    @Column(name = "tool_code", length = 255)
    private String toolCode;

    @Column(name = "intent_code", length = 255)
    private String intentCode;

    @Column(name = "state_code", length = 255)
    private String stateCode;

    @Column(name = "query_text", nullable = false, length = 1000)
    private String queryText;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "prepared_sql")
    private String preparedSql;

    @Column(name = "tags", length = 2000)
    private String tags;

    @Column(name = "api_hints", length = 2000)
    private String apiHints;

    @Column(name = "embedding")
    private String embedding;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json")
    private String metadataJson;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    private void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}

