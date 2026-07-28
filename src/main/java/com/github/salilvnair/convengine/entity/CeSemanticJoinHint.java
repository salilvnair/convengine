package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ce_semantic_join_hint")
@Data
public class CeSemanticJoinHint {

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "base_table", nullable = false)
    private String baseTable;

    @Column(name = "join_table", nullable = false)
    private String joinTable;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
