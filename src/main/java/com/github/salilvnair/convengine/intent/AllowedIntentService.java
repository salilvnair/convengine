package com.github.salilvnair.convengine.intent;

import com.github.salilvnair.convengine.repo.IntentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class AllowedIntentService {

    private final IntentRepository intentRepository;

    /**
     * Canonical source of truth for allowed intents.
     * Ordered by priority ASC (lower number = higher priority)
     */
    public List<AllowedIntent> allowedIntents() {
        return intentRepository.findByEnabledTrueOrderByPriorityAsc()
                .stream()
                .filter(i ->
                        i.getIntentCode() != null &&
                                !i.getIntentCode().isBlank()
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

        return allowedIntents()
                .stream()
                .anyMatch(i ->
                        i.code().equalsIgnoreCase(intentCode.trim())
                );
    }
}
