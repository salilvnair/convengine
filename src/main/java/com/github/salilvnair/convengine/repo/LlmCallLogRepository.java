package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeLlmCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmCallLogRepository
        extends JpaRepository<CeLlmCallLog, Long> {
}
