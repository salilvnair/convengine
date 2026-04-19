package com.github.salilvnair.convengine.builder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Join entity for the many-to-many relationship between agents and skills.
 */
@Entity
@Table(name = "ce_bs_agent_skill")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@IdClass(CeBsAgentSkill.AgentSkillId.class)
public class CeBsAgentSkill {

    @Id
    @Column(name = "agent_id")
    private String agentId;

    @Id
    @Column(name = "skill_id")
    private String skillId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentSkillId implements Serializable {
        private String agentId;
        private String skillId;
    }
}
