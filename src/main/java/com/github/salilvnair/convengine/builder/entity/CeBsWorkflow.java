package com.github.salilvnair.convengine.builder.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ce_bs_workflow")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CeBsWorkflow {

    @Id
    @Column(name = "workflow_id")
    private String workflowId;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "team_id")
    private String teamId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nodes")
    private String nodes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edges")
    private String edges;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sub_block_values")
    private String subBlockValues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private String metadata;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    private void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
