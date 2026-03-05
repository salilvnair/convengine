package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast;

import java.util.List;

public record AstValidationResult(
        boolean valid,
        List<String> errors
) {
}
