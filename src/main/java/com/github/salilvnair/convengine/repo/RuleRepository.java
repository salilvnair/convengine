package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RuleRepository extends JpaRepository<CeRule, Long> {
    List<CeRule> findByEnabledTrueOrderByPriorityAsc();
    List<CeRule> findByEnabledTrueAndPhaseOrderByPriorityAsc(String phase);

    @Query("""
            select r
            from CeRule r
            where r.enabled = true
              and r.phase = :phase
              and (
                  r.stateCode is null
                  or trim(r.stateCode) = ''
                  or upper(r.stateCode) = 'ANY'
                  or upper(r.stateCode) = upper(:state)
              )
            order by r.priority asc
            """)
    List<CeRule> findEligibleByPhaseAndStateOrderByPriorityAsc(@Param("phase") String phase,
                                                                @Param("state") String state);
}
