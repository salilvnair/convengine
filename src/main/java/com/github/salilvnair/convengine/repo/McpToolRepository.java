package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeMcpTool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpToolRepository extends JpaRepository<CeMcpTool, Long> {
    List<CeMcpTool> findByEnabledTrueOrderByToolGroupAscToolCodeAsc();
    Optional<CeMcpTool> findByToolCodeAndEnabledTrue(String toolCode);
}
