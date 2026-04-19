package com.github.salilvnair.convengine.builder.repo;

import com.github.salilvnair.convengine.builder.entity.CeBsWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BsWorkspaceRepository extends JpaRepository<CeBsWorkspace, String> {
}
