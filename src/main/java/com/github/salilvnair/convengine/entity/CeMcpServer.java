package com.github.salilvnair.convengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Required, DB-backed persistence for external MCP server configs
 * ({@link com.github.salilvnair.convengine.engine.mcp.McpServerConfig}) —
 * {@code McpRegistry} has no local-file fallback, so this table must exist
 * for any multi-replica deployment (e.g. AKS/k8s) where server config must
 * be shared across pods rather than living on one pod's local disk.
 */
@Getter
@Setter
@Entity
@Table(name = "ce_mcp_server")
public class CeMcpServer {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name")
    private String name;

    @Column(name = "transport", nullable = false)
    private String transport;

    @Column(name = "command")
    private String command;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "args")
    private List<String> args;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "env")
    private Map<String, String> env;

    @Column(name = "url")
    private String url;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers")
    private Map<String, String> headers;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
