package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.interceptor;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.*;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;

public interface AstCompilationInterceptor {

    default boolean supports(SemanticQueryContext context) {
        return true;
    }

    default void beforeCompile(CanonicalAst ast, SemanticQueryContext context) {
    }

    default CompiledSql afterCompile(CompiledSql sql, SemanticQueryContext context) {
        return sql;
    }

    default void onError(CanonicalAst ast, SemanticQueryContext context, Exception ex) {
    }
}
