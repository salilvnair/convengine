package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeContainerConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContainerConfigRepository
        extends JpaRepository<CeContainerConfig, Long> {

    List<CeContainerConfig>
    findByEnabledTrueAndIntentCodeAndStateCodeOrderByPriorityAsc(
            String intentCode,
            String stateCode
    );
}
