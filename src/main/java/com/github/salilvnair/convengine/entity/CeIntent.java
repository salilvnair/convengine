package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ce_intent")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CeIntent {
    @Id
    @Column(name = "intent_code")
    private String intentCode;

    @Column(name = "description")
    private String description;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
