package com.github.salilvnair.convengine.engine.agent.query.semantic.model;

public record SemanticRelationship(
        String name,
        String description,
        SemanticRelationshipEnd from,
        SemanticRelationshipEnd to,
        String type
) {
}
