package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeAgentDbTool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentDbToolRepository extends JpaRepository<CeAgentDbTool, Long> {
    Optional<CeAgentDbTool> findByTool_ToolCodeAndTool_EnabledTrue(String toolCode);
}
