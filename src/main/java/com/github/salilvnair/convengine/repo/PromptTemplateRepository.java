package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CePromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PromptTemplateRepository extends JpaRepository<CePromptTemplate, Long> {
    Optional<CePromptTemplate>
    findFirstByEnabledTrueAndResponseTypeAndIntentCodeOrderByCreatedAtDesc(
            String purpose,
            String intentCode
    );
}
