package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "ce_policy")
@Data
public class CePolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long policyId;

    private String ruleType;
    private String pattern;
    private String responseText;
    private int priority;
    private boolean enabled;
}
