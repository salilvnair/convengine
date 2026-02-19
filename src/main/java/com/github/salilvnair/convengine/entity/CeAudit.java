package com.github.salilvnair.convengine.entity;

import com.github.salilvnair.convengine.entity.converter.OffsetDateTimeStringConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ce_audit")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CeAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long auditId;

    @Column(nullable = false, name = "conversation_id")
    private UUID conversationId;

    /**
     * High-level stage identifier:
     * MESSAGE_RECEIVED
     * POLICY_HIT
     * INTENT_CLASSIFIED
     * RULE_MATCHED
     * EXTRACTION_RAN
     * CONTEXT_MERGED
     * RESPONSE_SELECTED
     * RESPONSE_RENDERED
     * ERROR
     */
    @Column(nullable = false)
    private String stage;

    /**
     * Arbitrary JSON payload describing
     * what happened at this stage.
     */
    @Column(nullable = false, name = "payload_json")
    private String payloadJson;

    @Convert(converter = OffsetDateTimeStringConverter.class)
    @Column(nullable = false, name = "created_at")
    private OffsetDateTime createdAt;
}
