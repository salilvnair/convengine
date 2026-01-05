package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeIntentClassifier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntentClassifierRepository
        extends JpaRepository<CeIntentClassifier, Long> {

    List<CeIntentClassifier> findByEnabledTrueOrderByPriorityAsc();
}
