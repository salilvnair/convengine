package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SemanticIntentRule(
        String description,
        @JsonProperty("match_any") List<String> matchAny,
        @JsonProperty("must_contain") List<String> mustContain,
        @JsonProperty("force_entity") String forceEntity,
        @JsonProperty("force_mode") String forceMode,
        @JsonProperty("force_mode_even_with_metrics") Boolean forceModeEvenWithMetrics,
        @JsonProperty("force_select") List<String> forceSelect,
        @JsonProperty("enforce_where") List<SemanticIntentFilter> enforceWhere,
        @JsonProperty("enforce_exists") List<SemanticIntentExists> enforceExists,
        @JsonProperty("field_remaps") List<SemanticIntentFieldRemap> fieldRemaps
) {
    public SemanticIntentRule {
        matchAny = matchAny == null ? List.of() : List.copyOf(matchAny);
        mustContain = mustContain == null ? List.of() : List.copyOf(mustContain);
        forceSelect = forceSelect == null ? List.of() : List.copyOf(forceSelect);
        enforceWhere = enforceWhere == null ? List.of() : List.copyOf(enforceWhere);
        enforceExists = enforceExists == null ? List.of() : List.copyOf(enforceExists);
        fieldRemaps = fieldRemaps == null ? List.of() : List.copyOf(fieldRemaps);
    }
}
