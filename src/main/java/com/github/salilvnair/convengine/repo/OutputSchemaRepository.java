package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeOutputSchema;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OutputSchemaRepository
        extends JpaRepository<CeOutputSchema, Long> {

    Optional<CeOutputSchema> findFirstByEnabledTrueAndIntentCodeAndStateCodeOrderByPriorityAsc(
            String intentCode,
            String stateCode
    );
}
