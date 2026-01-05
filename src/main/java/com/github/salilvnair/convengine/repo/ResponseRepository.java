package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResponseRepository extends JpaRepository<CeResponse, Long> {

    Optional<CeResponse> findFirstByEnabledTrueAndStateCodeAndIntentCodeOrderByPriorityAsc(
            String stateCode,
            String intentCode
    );

    Optional<CeResponse> findFirstByEnabledTrueAndStateCodeAndIntentCodeIsNullOrderByPriorityAsc(
            String stateCode
    );

    Optional<CeResponse> findFirstByEnabledTrueAndStateCodeOrderByPriorityAsc(
            String stateCode
    );
}
