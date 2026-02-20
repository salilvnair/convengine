package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import com.github.salilvnair.convengine.entity.converter.OffsetDateTimeStringConverter;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ce_validation_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CeValidationSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "snapshot_id")
    private Long snapshotId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "intent_code")
    private String intentCode;

    @Column(name = "state_code")
    private String stateCode;

    @Column(name = "schema_id")
    private Long schemaId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_tables")
    private String validationTables;

    @Column(name = "validation_decision", columnDefinition = "text")
    private String validationDecision;

    @Convert(converter = OffsetDateTimeStringConverter.class)
    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
