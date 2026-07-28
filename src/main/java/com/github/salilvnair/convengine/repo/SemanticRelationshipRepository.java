package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeSemanticRelationship;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemanticRelationshipRepository extends JpaRepository<CeSemanticRelationship, Long> {
}
