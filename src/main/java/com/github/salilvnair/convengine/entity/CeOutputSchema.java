package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ce_output_schema")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CeOutputSchema {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schema_id")
    private Long schemaId;

    @Column(name = "intent_code", nullable = false)
    private String intentCode;

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    @Column(name = "json_schema", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String jsonSchema;

    @Column(name = "description")
    private String description;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "priority", nullable = false)
    private Integer priority;
}
