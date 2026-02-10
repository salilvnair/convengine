package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "ce_config",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"config_type", "config_key"})
        }
)
public class CeConfig {

    @Id
    @Column(name = "config_id")
    private Long configId;

    @Column(name = "config_type", nullable = false)
    private String configType;

    @Column(name = "config_key", nullable = false)
    private String configKey;

    @Column(name = "config_value", nullable = false)
    private String configValue;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}