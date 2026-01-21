package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "ce_mcp_db_tool")
public class CeMcpDbTool {

    @Id
    @Column(name = "tool_id")
    private Long toolId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "tool_id")
    private CeMcpTool tool;

    @Column(name = "dialect", nullable = false)
    private String dialect;

    @Column(name = "sql_template", nullable = false)
    private String sqlTemplate;

    @Column(name = "param_schema", nullable = false, columnDefinition = "jsonb")
    private String paramSchema;

    @Column(name = "safe_mode", nullable = false)
    private boolean safeMode = true;

    @Column(name = "max_rows", nullable = false)
    private int maxRows = 200;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
