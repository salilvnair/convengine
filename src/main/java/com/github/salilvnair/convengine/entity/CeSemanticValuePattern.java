package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ce_semantic_value_pattern")
@Data
public class CeSemanticValuePattern {

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_field", nullable = false)
    private String fromField;

    @Column(name = "to_field", nullable = false)
    private String toField;

    @Column(name = "value_starts_with", nullable = false)
    private String valueStartsWith;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
