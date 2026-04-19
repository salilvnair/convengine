package com.github.salilvnair.convengine.builder.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ce_bs_agent")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CeBsAgent {

    @Id
    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "pool_id", nullable = false)
    private String poolId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "model")
    private String model;

    @Column(name = "provider")
    private String provider;

    @Column(name = "system_prompt")
    private String systemPrompt;

    @Column(name = "user_prompt")
    private String userPrompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_schema")
    private String inputSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_schema")
    private String outputSchema;

    @Column(name = "strict_input")
    private Boolean strictInput;

    @Column(name = "strict_output")
    private Boolean strictOutput;

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
