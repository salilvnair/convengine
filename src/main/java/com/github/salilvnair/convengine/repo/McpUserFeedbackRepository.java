package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeMcpUserFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpUserFeedbackRepository extends JpaRepository<CeMcpUserFeedback, Long> {
}

