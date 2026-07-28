package com.github.salilvnair.convengine.builder.repo;

import com.github.salilvnair.convengine.builder.entity.CeBsSkill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BsSkillRepository extends JpaRepository<CeBsSkill, String> {
    List<CeBsSkill> findByWorkspaceId(String workspaceId);
}
