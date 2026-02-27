package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "ce_verbose")
public class CeVerbose {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "verbose_id")
    private Long verboseId;

    @Column(name = "intent_code", nullable = false)
    private String intentCode;

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    @Column(name = "step_match", nullable = false)
    private String stepMatch;

    @Column(name = "step_value", nullable = false)
    private String stepValue;

    @Column(name = "determinant", nullable = false)
    private String determinant;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "tool_code")
    private String toolCode;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
