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
    @Column(name = "response_id")
    private Long responseId;

    @Column(name = "intent_code")
    private String intentCode;

    @Column(name = "state_code")
    private String stateCode;

    @Column(name = "output_format")
    private String outputFormat;   // TEXT | JSON

    @Column(name = "response_type")
    private String responseType;   // EXACT | DERIVED

    @Column(columnDefinition = "text", name = "exact_text")
    private String exactText;

    @Column(columnDefinition = "text", name = "derivation_hint")
    private String derivationHint;

    @Column(columnDefinition = "jsonb", name = "json_schema")
    @JdbcTypeCode(SqlTypes.JSON)
    private String jsonSchema;

    @Column(name = "priority")
    private int priority;

    @Column(name = "enabled")
    private boolean enabled;
}
