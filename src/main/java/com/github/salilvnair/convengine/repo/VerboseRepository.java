package com.github.salilvnair.convengine.repo;

import com.github.salilvnair.convengine.entity.CeVerbose;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerboseRepository extends JpaRepository<CeVerbose, Long> {
}
