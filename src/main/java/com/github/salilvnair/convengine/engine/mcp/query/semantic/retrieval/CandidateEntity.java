package com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval;

import java.util.List;

public record CandidateEntity(
        String name,
        double score,
        double deterministicScore,
        double vectorScore,
        List<String> reasons
) {
}
