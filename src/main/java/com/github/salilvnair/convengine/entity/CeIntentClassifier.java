package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "ce_intent_classifier")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CeIntentClassifier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "classifier_id")
    private Long classifierId;

    @Column(name = "intent_code", nullable = false)
    private String intentCode;

    @Column(name = "state_code", nullable = false)
    private String stateCode = "UNKNOWN";

    @Column(name = "rule_type", nullable = false)
    private String ruleType; // REGEX | CONTAINS | STARTS_WITH

    @Column(name = "pattern", nullable = false)
    private String pattern;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "enabled")
    private boolean enabled;

    @Column(name = "description")
    private String description;
}
