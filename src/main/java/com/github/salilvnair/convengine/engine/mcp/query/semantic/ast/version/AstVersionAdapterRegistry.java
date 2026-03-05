package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.version;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AstVersionAdapterRegistry {

    private final Map<String, AstVersionAdapter> adaptersByVersion;

    public AstVersionAdapterRegistry(List<AstVersionAdapter> adapters) {
        this.adaptersByVersion = adapters == null
                ? Map.of()
                : adapters.stream().collect(Collectors.toMap(
                a -> normalize(a.version()),
                Function.identity(),
                (a, b) -> a
        ));
    }

    public AstVersionAdapter resolve(String version) {
        String key = normalize(version);
        AstVersionAdapter adapter = adaptersByVersion.get(key);
        if (adapter == null) {
            throw new IllegalStateException("No AstVersionAdapter found for astVersion=" + version);
        }
        return adapter;
    }

    private String normalize(String version) {
        if (version == null || version.isBlank()) {
            return "v1";
        }
        return version.trim().toLowerCase(Locale.ROOT);
    }
}
