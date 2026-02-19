package com.github.salilvnair.convengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "param_schema", nullable = false)
    private String paramSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_identifiers")
    private Map<String, Set<String>> allowedIdentifiers;

    @Column(name = "safe_mode", nullable = false)
    private boolean safeMode = true;

    @Column(name = "max_rows", nullable = false)
    private int maxRows = 200;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
