package com.github.salilvnair.convengine.builder.repo;

import com.github.salilvnair.convengine.builder.entity.CeBsAgentPool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BsAgentPoolRepository extends JpaRepository<CeBsAgentPool, String> {
    List<CeBsAgentPool> findByTeamId(String teamId);
}
