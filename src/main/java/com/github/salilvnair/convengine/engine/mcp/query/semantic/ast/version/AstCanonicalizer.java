package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import org.springframework.stereotype.Component;

@Component
public class AstCanonicalizer {

    private final AstVersionAdapterRegistry registry;
    private final ObjectMapper objectMapper;

    public AstCanonicalizer(AstVersionAdapterRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    public CanonicalAst fromRawJson(String astVersion, String rawJson) {
        AstVersionAdapter adapter = registry.resolve(astVersion);
        return adapter.toCanonical(new AstPayload(astVersion, rawJson));
    }

    public CanonicalAst fromV1(SemanticQueryAstV1 ast) {
        try {
            String raw = objectMapper.writeValueAsString(ast);
            return fromRawJson(ast == null ? "v1" : ast.astVersion(), raw);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize v1 AST for canonicalization", ex);
        }
    }
}
