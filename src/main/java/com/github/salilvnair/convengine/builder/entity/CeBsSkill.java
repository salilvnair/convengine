package com.github.salilvnair.convengine.builder.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ce_bs_skill")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CeBsSkill {

    @Id
    @Column(name = "skill_id")
    private String skillId;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "language")
    private String language;

    @Column(name = "source")
    private String source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_schema")
    private String inputSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_schema")
    private String outputSchema;

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
