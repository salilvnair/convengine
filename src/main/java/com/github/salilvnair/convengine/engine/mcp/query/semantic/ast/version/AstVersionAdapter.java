package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.version;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;

public interface AstVersionAdapter {
    String version();
    CanonicalAst toCanonical(AstPayload payload);
}
