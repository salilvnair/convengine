package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CePolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyRepository extends JpaRepository<CePolicy, Long> {
    List<CePolicy> findByEnabledTrueOrderByPriorityAsc();
}
