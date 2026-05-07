package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeAgentTool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentToolRepository extends JpaRepository<CeAgentTool, Long> {
  List<CeAgentTool> findByEnabledTrueOrderByToolGroupAscToolCodeAsc();

  Optional<CeAgentTool> findByToolCodeAndEnabledTrue(String toolCode);

  @Query("""
      SELECT t
      FROM CeAgentTool t
      WHERE t.enabled = true
        AND (t.intentCode IS NULL OR t.intentCode = :intentCode)
        AND (t.stateCode IS NULL OR t.stateCode = :stateCode)
      ORDER BY t.toolGroup ASC, t.toolCode ASC
      """)
  List<CeAgentTool> findEnabledByIntentAndState(@Param("intentCode") String intentCode,
      @Param("stateCode") String stateCode);

  @Query("""
      SELECT t
      FROM CeAgentTool t
      WHERE t.enabled = true
        AND t.toolCode = :toolCode
        AND (t.intentCode IS NULL OR t.intentCode = :intentCode)
        AND (t.stateCode IS NULL OR t.stateCode = :stateCode)
      """)
  Optional<CeAgentTool> findByToolCodeEnabledAndIntentAndState(@Param("toolCode") String toolCode,
      @Param("intentCode") String intentCode, @Param("stateCode") String stateCode);
}
