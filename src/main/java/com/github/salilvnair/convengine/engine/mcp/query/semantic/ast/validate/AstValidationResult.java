package com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.*;

import java.util.List;

public record AstValidationResult(
        boolean valid,
        List<String> errors
) {
}
