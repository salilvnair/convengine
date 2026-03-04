package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ce_mcp_user_query_knowledge")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CeMcpUserQueryKnowledge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

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

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    private void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}

