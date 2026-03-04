package com.github.salilvnair.convengine.engine.mcp.knowledge;

import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;

import java.util.List;
import java.util.Map;

/**
 * Extension point for semantic-catalog vector ranking.
 *
 * Consumer applications can provide their own implementation (for example,
 * DB-native pgvector cosine ranking) and set a higher Spring @Order priority.
 *
 * Contract:
 * - Return rows ordered by most relevant first.
 * - Optionally attach a numeric score under "_vector_score" or "vectorScore".
 * - Return empty list to indicate no vector result; caller may fallback to lexical ranking.
 */
public interface SemanticCatalogVectorSearchInterceptor {

    /**
     * Whether this interceptor should run for the given vector-search config.
     */
    boolean supports(ConvEngineMcpConfig.Db.Knowledge.VectorSearch config);

    /**
     * @param sourceType "query" or "schema"
     * @param question user question
     * @param rows in-memory candidate rows loaded from semantic catalog table
     * @param cfg semantic-catalog config
     * @return ranked rows (can be subset) with optional score metadata
     */
    List<Map<String, Object>> rank(
            String sourceType,
            String question,
            List<Map<String, Object>> rows,
            ConvEngineMcpConfig.Db.Knowledge cfg);
}

