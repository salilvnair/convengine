package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeSemanticEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemanticEntityRepository extends JpaRepository<CeSemanticEntity, Long> {
}
