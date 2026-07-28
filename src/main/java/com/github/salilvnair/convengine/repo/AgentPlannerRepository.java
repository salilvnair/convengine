package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeAgentPlanner;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentPlannerRepository extends JpaRepository<CeAgentPlanner, Long> {
}
