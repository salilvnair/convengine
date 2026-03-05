package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order()
@RequiredArgsConstructor
public class V1AstVersionAdapter implements AstVersionAdapter {

    private final ObjectMapper objectMapper;
    private final V1CanonicalAstMapper mapper;

    @Override
    public String version() {
        return "v1";
    }

    @Override
    public CanonicalAst toCanonical(AstPayload payload) {
        try {
            SemanticQueryAstV1 ast = objectMapper.readValue(payload.rawJson(), SemanticQueryAstV1.class);
            return mapper.from(ast);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse/convert v1 AST payload", ex);
        }
    }
}
