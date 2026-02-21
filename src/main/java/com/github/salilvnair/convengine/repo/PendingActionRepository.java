package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CePendingAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PendingActionRepository extends JpaRepository<CePendingAction, Long> {

    @Query("""
            select p
            from CePendingAction p
            where p.enabled = true
              and upper(p.actionKey) = upper(:actionKey)
              and (
                    p.intentCode is null
                    or trim(p.intentCode) = ''
                    or upper(p.intentCode) = 'ANY'
                    or upper(p.intentCode) = upper(:intent)
              )
              and (
                    p.stateCode is null
                    or trim(p.stateCode) = ''
                    or upper(p.stateCode) = 'ANY'
                    or upper(p.stateCode) = upper(:state)
              )
            order by p.priority asc, p.pendingActionId asc
            """)
    List<CePendingAction> findEligibleByActionIntentAndStateOrderByPriorityAsc(
            @Param("actionKey") String actionKey,
            @Param("intent") String intent,
            @Param("state") String state
    );

    @Query("""
            select p
            from CePendingAction p
            where p.enabled = true
              and (
                    p.intentCode is null
                    or trim(p.intentCode) = ''
                    or upper(p.intentCode) = 'ANY'
                    or upper(p.intentCode) = upper(:intent)
              )
              and (
                    p.stateCode is null
                    or trim(p.stateCode) = ''
                    or upper(p.stateCode) = 'ANY'
                    or upper(p.stateCode) = upper(:state)
              )
            order by p.priority asc, p.pendingActionId asc
            """)
    List<CePendingAction> findEligibleByIntentAndStateOrderByPriorityAsc(
            @Param("intent") String intent,
            @Param("state") String state
    );
}
