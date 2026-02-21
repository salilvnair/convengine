package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "ce_pending_action")
@Data
public class CePendingAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pending_action_id")
    private Long pendingActionId;

    @Column(name = "intent_code")
    private String intentCode;

    @Column(name = "state_code")
    private String stateCode;

    @Column(name = "action_key")
    private String actionKey;

    @Column(name = "bean_name")
    private String beanName;

    @Column(name = "method_names")
    private String methodNames;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "enabled")
    private boolean enabled;

    @Column(name = "description")
    private String description;
}
