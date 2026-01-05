package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleRepository extends JpaRepository<CeRule, Long> {
    List<CeRule> findByEnabledTrueOrderByPriorityAsc();
}
