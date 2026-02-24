package com.github.salilvnair.convengine.intent;

import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import com.github.salilvnair.convengine.repo.IntentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class AllowedIntentService {

    private final StaticConfigurationCacheService cacheService;
    private static final Set<String> EXCLUDED_INTENTS = Set.of("UNKNOWN");

    /**
     * Canonical source of truth for allowed intents.
     * Ordered by priority ASC (lower number = higher priority)
     */
    public List<AllowedIntent> allowedIntents() {
        return cacheService.findEnabledIntents()
                .stream()
                .filter(i ->
                        i.getIntentCode() != null &&
                                !i.getIntentCode().isBlank() &&
                                !EXCLUDED_INTENTS.contains(i.getIntentCode().trim().toUpperCase())
                )
                .map(i ->
                        new AllowedIntent(
                                i.getIntentCode().trim(),
                                i.getDescription(),
                                i.getLlmHint(),
                                i.getPriority()
                        )
                )
                .toList();
    }

    /**
     * Hard gate check (used AFTER agent resolution)
     */
    public boolean isAllowed(String intentCode) {
        if (intentCode == null || intentCode.isBlank()) return false;
        if (EXCLUDED_INTENTS.contains(intentCode.trim().toUpperCase())) return false;

        return allowedIntents()
                .stream()
                .anyMatch(i ->
                        i.code().equalsIgnoreCase(intentCode.trim())
                );
    }
}
