package com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval;

import java.util.List;

public record RetrievalResult(
        String question,
        List<CandidateEntity> candidateEntities,
        List<CandidateTable> candidateTables,
        String confidence
) {
}
