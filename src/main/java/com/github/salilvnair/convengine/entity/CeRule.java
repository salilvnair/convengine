package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "ce_rule")
@Data
public class CeRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long ruleId;
    @Column(name = "intent_code", nullable = false)
    private String intentCode;
    @Column(name = "state_code", nullable = false)
    private String stateCode;
    @Column(name = "rule_type")
    private String ruleType;
    @Column(name = "match_pattern")
    private String matchPattern;
    @Column(name = "action")
    private String action;
    @Column(name = "action_value")
    private String actionValue;
    @Column(name = "phase")
    private String phase;
    @Column(name = "priority")
    private int priority;
    @Column(name = "enabled")
    private boolean enabled;
}
