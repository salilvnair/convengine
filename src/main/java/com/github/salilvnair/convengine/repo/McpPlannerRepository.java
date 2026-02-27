package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeMcpPlanner;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpPlannerRepository extends JpaRepository<CeMcpPlanner, Long> {
}
