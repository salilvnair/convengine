package com.github.salilvnair.convengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
@Table(name = "ce_semantic_query_failures")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CeSemanticQueryFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "question", nullable = false)
    private String question;

    @Column(name = "generated_sql")
    private String generatedSql;

    @Column(name = "corrected_sql")
    private String correctSql;

    @Column(name = "root_cause_code", length = 255)
    private String rootCause;

    @Column(name = "reason")
    private String reason;

    @Column(name = "stage_code", length = 100)
    private String stage;

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
