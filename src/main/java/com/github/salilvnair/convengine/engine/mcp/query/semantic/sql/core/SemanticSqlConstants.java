package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core;

public final class SemanticSqlConstants {
    private SemanticSqlConstants() {
    }

    public static final String ERROR_AST_MISSING = "Canonical AST missing for semantic SQL compile.";
    public static final String ERROR_UNKNOWN_ENTITY_PREFIX = "Unknown entity in AST: ";
    public static final String ERROR_SQL_NOT_PRODUCED = "No compiled SQL produced by AST clause handlers.";
    public static final String ERROR_UNSUPPORTED_WINDOW_FUNCTION_PREFIX = "Unsupported window function: ";

    public static final String WINDOW_FN_ROW_NUMBER = "ROW_NUMBER";
}

