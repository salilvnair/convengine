package com.github.salilvnair.convengine.audit.persistence;

import com.github.salilvnair.convengine.config.ConvEngineAuditConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AuditPersistenceStrategyFactory {

    private final ConvEngineAuditConfig auditConfig;
    private final List<AuditPersistenceStrategy> strategies;

    public AuditPersistenceStrategy currentStrategy() {
        ConvEngineAuditConfig.Mode configuredMode = auditConfig.getPersistence() == null
                ? ConvEngineAuditConfig.Mode.IMMEDIATE
                : auditConfig.getPersistence().getMode();
        final ConvEngineAuditConfig.Mode mode = configuredMode == null
                ? ConvEngineAuditConfig.Mode.IMMEDIATE
                : configuredMode;
        return strategies.stream()
                .filter(strategy -> strategy.supports(mode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No audit persistence strategy for mode: " + mode));
    }
}
