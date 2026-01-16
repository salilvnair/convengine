package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeContainerConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ContainerConfigRepository
        extends JpaRepository<CeContainerConfig, Long> {

    // -----------------------------------------------------
    // 1. Exact intent + state (EXPLICIT JPQL)
    // -----------------------------------------------------
    @Query("""
        SELECT c
        FROM CeContainerConfig c
        WHERE c.enabled = true
          AND c.intentCode = :intentCode
          AND c.stateCode = :stateCode
        ORDER BY c.priority ASC
    """)
    List<CeContainerConfig> findByIntentAndState(
            @Param("intentCode") String intentCode,
            @Param("stateCode") String stateCode
    );

    // -----------------------------------------------------
    // 2. Intent fallback (intent IS NULL)
    // -----------------------------------------------------
    @Query("""
        SELECT c
        FROM CeContainerConfig c
        WHERE c.enabled = true
          AND c.intentCode IS NULL
          AND c.stateCode = :stateCode
        ORDER BY c.priority ASC
    """)
    List<CeContainerConfig> findFallbackByState(
            @Param("stateCode") String stateCode
    );

    // -----------------------------------------------------
    // 3. Global fallback
    // -----------------------------------------------------
    @Query("""
        SELECT c
        FROM CeContainerConfig c
        WHERE c.enabled = true
          AND c.intentCode IS NULL
          AND c.stateCode IS NULL
        ORDER BY c.priority ASC
    """)
    List<CeContainerConfig> findGlobalFallback();
}
