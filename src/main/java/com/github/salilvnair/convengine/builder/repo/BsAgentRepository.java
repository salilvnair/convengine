package com.github.salilvnair.convengine.builder.repo;

import com.github.salilvnair.convengine.builder.entity.CeBsAgent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BsAgentRepository extends JpaRepository<CeBsAgent, String> {
    List<CeBsAgent> findByPoolId(String poolId);
}
