package com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core;

import java.util.List;
import java.util.Map;

public record CandidateEntity(
        String name,
        double score,
        double deterministicScore,
        double vectorScore,
        List<String> reasons,
        Map<String, Double> signalScores
) {
    public CandidateEntity(
            String name,
            double score,
            double deterministicScore,
            double vectorScore,
            List<String> reasons
    ) {
        this(name, score, deterministicScore, vectorScore, reasons, Map.of());
    }
}
