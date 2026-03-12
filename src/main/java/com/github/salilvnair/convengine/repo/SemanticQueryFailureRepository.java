package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeSemanticQueryFailure;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemanticQueryFailureRepository extends JpaRepository<CeSemanticQueryFailure, Long> {
}
