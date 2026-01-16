package com.github.salilvnair.convengine.intent;

import com.github.salilvnair.convengine.repo.IntentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class AllowedIntentService {

    private final IntentRepository intentRepository;

    public Set<String> allowedIntentCodes() {
        return intentRepository.findByEnabledTrueOrderByPriorityAsc()
                .stream()
                .map(i -> i.getIntentCode() == null ? null : i.getIntentCode().trim())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());
    }

    public boolean isAllowed(String intentCode) {
        if (intentCode == null || intentCode.isBlank()) return false;
        return allowedIntentCodes().contains(intentCode.trim());
    }
}
