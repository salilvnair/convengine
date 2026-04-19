package com.github.salilvnair.convengine.builder.repo;

import com.github.salilvnair.convengine.builder.entity.CeBsTeam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BsTeamRepository extends JpaRepository<CeBsTeam, String> {
    List<CeBsTeam> findByWorkspaceId(String workspaceId);
}
