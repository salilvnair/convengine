package com.github.salilvnair.convengine.builder.repo;

import com.github.salilvnair.convengine.builder.entity.CeBsWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BsWorkflowRepository extends JpaRepository<CeBsWorkflow, String> {
    List<CeBsWorkflow> findByWorkspaceId(String workspaceId);
    List<CeBsWorkflow> findByTeamId(String teamId);
}
