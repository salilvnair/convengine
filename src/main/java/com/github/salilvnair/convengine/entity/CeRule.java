package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "ce_rule")
@Data
public class CeRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long ruleId;

    private String intentCode;
    private String ruleType;
    private String matchPattern;
    private String action;
    private String actionValue;
    private int priority;
    private boolean enabled;
}
