package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

public record SemanticRelationship(
        String name,
        String description,
        SemanticRelationshipEnd from,
        SemanticRelationshipEnd to,
        String type
) {
}
