package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CeConfigRepository extends JpaRepository<CeConfig, Long> {

    Optional<CeConfig> findByConfigTypeAndConfigKeyAndEnabledTrue(
            String configType,
            String configKey
    );
}