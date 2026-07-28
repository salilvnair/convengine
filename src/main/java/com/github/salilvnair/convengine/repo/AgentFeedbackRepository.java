package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeAgentFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentFeedbackRepository extends JpaRepository<CeAgentFeedback, Long> {
}

