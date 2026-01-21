package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeMcpDbTool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface McpDbToolRepository extends JpaRepository<CeMcpDbTool, Long> {
    Optional<CeMcpDbTool> findByTool_ToolCodeAndTool_EnabledTrue(String toolCode);
}
