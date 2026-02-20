package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "ce_mcp_tool")
public class CeMcpTool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tool_id")
    private Long toolId;

    @Column(name = "tool_code", nullable = false, unique = true)
    private String toolCode;

    @Column(name = "tool_group", nullable = false)
    private String toolGroup;

    @Column(name = "intent_code")
    private String intentCode;

    @Column(name = "state_code")
    private String stateCode;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
