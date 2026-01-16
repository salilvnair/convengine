package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeIntent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IntentRepository extends JpaRepository<CeIntent, String> {

    List<CeIntent> findByEnabledTrueOrderByPriorityAsc();

    Optional<CeIntent> findByIntentCodeAndEnabledTrue(String intentCode);
}
