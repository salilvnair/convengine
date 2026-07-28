package com.github.salilvnair.convengine.builder.repo;

import com.github.salilvnair.convengine.builder.entity.CeBsAgentSkill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BsAgentSkillRepository
        extends JpaRepository<CeBsAgentSkill, CeBsAgentSkill.AgentSkillId> {
    List<CeBsAgentSkill> findByAgentId(String agentId);
    void deleteByAgentId(String agentId);
}
