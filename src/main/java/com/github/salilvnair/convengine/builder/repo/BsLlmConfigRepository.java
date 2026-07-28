package com.github.salilvnair.convengine.builder.repo;

import com.github.salilvnair.convengine.builder.entity.CeBsLlmConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BsLlmConfigRepository extends JpaRepository<CeBsLlmConfig, String> {
}
