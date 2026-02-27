package com.github.salilvnair.convengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "ce_mcp_planner")
public class CeMcpPlanner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "planner_id")
    private Long plannerId;

    @Column(name = "intent_code", nullable = false)
    private String intentCode;

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    @Column(name = "system_prompt", nullable = false)
    private String systemPrompt;

    @Column(name = "user_prompt", nullable = false)
    private String userPrompt;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
