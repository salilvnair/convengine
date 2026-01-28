package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "ce_policy")
@Data
public class CePolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_id")
    private Long policyId;

    @Column(name = "rule_type")
    private String ruleType;

    @Column(name = "pattern")
    private String pattern;

    @Column(name = "response_text")
    private String responseText;

    @Column(name = "priority")
    private int priority;

    @Column(name = "enabled")
    private boolean enabled;
}
