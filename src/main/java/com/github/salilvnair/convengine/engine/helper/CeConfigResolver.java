package com.github.salilvnair.convengine.engine.helper;

import com.github.salilvnair.convengine.entity.CeConfig;
import com.github.salilvnair.convengine.repo.CeConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CeConfigResolver {

    private final CeConfigRepository repo;

    public double resolveDouble(Object configType, String configKey, double defaultValue) {
        String type = configType.getClass().getSimpleName();
        return repo.findByConfigTypeAndConfigKeyAndEnabledTrue(type, configKey)
                .map(c -> parseDouble(c.getConfigValue(), defaultValue))
                .orElse(defaultValue);
    }

    public int resolveInt(Object configType, String configKey, int defaultValue) {
        String type = configType.getClass().getSimpleName();
        return repo.findByConfigTypeAndConfigKeyAndEnabledTrue(type, configKey)
                .map(c -> parseInt(c.getConfigValue(), defaultValue))
                .orElse(defaultValue);
    }

    public String resolveString(Object configType, String configKey, String defaultValue) {
        String type = configType.getClass().getSimpleName();
        return repo.findByConfigTypeAndConfigKeyAndEnabledTrue(type, configKey)
                .map(CeConfig::getConfigValue)
                .orElse(defaultValue);
    }

    public boolean resolveBoolean(Object configType, String configKey, boolean defaultValue) {
        String type = configType.getClass().getSimpleName();
        return repo.findByConfigTypeAndConfigKeyAndEnabledTrue(type, configKey)
                .map(c -> parseBoolean(c.getConfigValue(), defaultValue))
                .orElse(defaultValue);
    }

    private double parseDouble(String v, double def) {
        try {
            return Double.parseDouble(v);
        } catch (Exception e) {
            return def;
        }
    }

    private int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return def;
        }
    }

    private boolean parseBoolean(String v, boolean def) {
        if (v == null) {
            return def;
        }
        String normalized = v.trim().toLowerCase();
        return switch (normalized) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> def;
        };
    }
}
