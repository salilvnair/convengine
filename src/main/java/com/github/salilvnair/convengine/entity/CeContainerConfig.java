package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ce_container_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CeContainerConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "intent_code", nullable = false)
    private String intentCode;

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    @Column(name = "page_id", nullable = false)
    private Integer pageId;

    @Column(name = "section_id", nullable = false)
    private Integer sectionId;

    @Column(name = "container_id", nullable = false)
    private Integer containerId;

    /**
     * Must EXACTLY match JSON schema field name
     */
    @Column(name = "input_param_name", nullable = false)
    private String inputParamName;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
