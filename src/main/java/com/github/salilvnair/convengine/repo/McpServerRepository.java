package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeMcpServer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerRepository extends JpaRepository<CeMcpServer, String> {
}
