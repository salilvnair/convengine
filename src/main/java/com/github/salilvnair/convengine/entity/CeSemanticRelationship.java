package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ce_semantic_relationship")
@Data
public class CeSemanticRelationship {

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "relationship_name", nullable = false)
    private String relationshipName;

    @Column(name = "description")
    private String description;

    @Column(name = "from_table", nullable = false)
    private String fromTable;

    @Column(name = "from_column", nullable = false)
    private String fromColumn;

    @Column(name = "to_table", nullable = false)
    private String toTable;

    @Column(name = "to_column", nullable = false)
    private String toColumn;

    @Column(name = "relation_type")
    private String relationType;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
