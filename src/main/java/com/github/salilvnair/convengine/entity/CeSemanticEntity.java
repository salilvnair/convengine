package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ce_semantic_entity")
@Data
public class CeSemanticEntity {

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_name", nullable = false)
    private String entityName;

    @Column(name = "description")
    private String description;

    @Column(name = "primary_table")
    private String primaryTable;

    @Column(name = "related_tables")
    private String relatedTables;

    @Column(name = "synonyms")
    private String synonyms;

    @Column(name = "fields_json", columnDefinition = "jsonb")
    private String fieldsJson;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
