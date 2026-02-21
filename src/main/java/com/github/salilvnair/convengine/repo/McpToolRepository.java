package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeMcpTool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface McpToolRepository extends JpaRepository<CeMcpTool, Long> {
  List<CeMcpTool> findByEnabledTrueOrderByToolGroupAscToolCodeAsc();

  Optional<CeMcpTool> findByToolCodeAndEnabledTrue(String toolCode);

  @Query("""
      SELECT t
      FROM CeMcpTool t
      WHERE t.enabled = true
        AND (t.intentCode IS NULL OR t.intentCode = :intentCode)
        AND (t.stateCode IS NULL OR t.stateCode = :stateCode)
      ORDER BY t.toolGroup ASC, t.toolCode ASC
      """)
  List<CeMcpTool> findEnabledByIntentAndState(@Param("intentCode") String intentCode,
      @Param("stateCode") String stateCode);

  @Query("""
      SELECT t
      FROM CeMcpTool t
      WHERE t.enabled = true
        AND t.toolCode = :toolCode
        AND (t.intentCode IS NULL OR t.intentCode = :intentCode)
        AND (t.stateCode IS NULL OR t.stateCode = :stateCode)
      """)
  Optional<CeMcpTool> findByToolCodeEnabledAndIntentAndState(@Param("toolCode") String toolCode,
      @Param("intentCode") String intentCode, @Param("stateCode") String stateCode);
}
