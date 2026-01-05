package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ce_response")
@Data
public class CeResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long responseId;

    private String intentCode;
    private String stateCode;

    private String outputFormat;   // TEXT | JSON
    private String responseType;   // EXACT | DERIVED

    @Column(columnDefinition = "text")
    private String exactText;

    @Column(columnDefinition = "text")
    private String derivationHint;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String jsonSchema;

    private int priority;
    private boolean enabled;
}
