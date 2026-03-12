package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate.AstValidationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompiledSql;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticExecutionResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;

import java.util.Map;

public class SemanticQueryContext {

    private final String question;
    private final EngineSession session;

    private RetrievalResult retrieval;
    private JoinPathPlan joinPath;
    private AstGenerationResult astGeneration;
    private CanonicalAst canonicalAst;
    private AstValidationResult astValidation;
    private CompiledSql compiledSql;
    private SemanticExecutionResult executionResult;
    private String summary;
    private boolean clarificationRequired;
    private String clarificationQuestion;
    private String clarificationReason;
    private Double clarificationConfidence;
    private Map<String, Object> clarificationDiagnostics;

    public SemanticQueryContext(String question, EngineSession session) {
        this.question = question == null ? "" : question;
        this.session = session;
    }

    public String question() {
        return question;
    }

    public EngineSession session() {
        return session;
    }

    public RetrievalResult retrieval() {
        return retrieval;
    }

    public void retrieval(RetrievalResult retrieval) {
        this.retrieval = retrieval;
    }

    public JoinPathPlan joinPath() {
        return joinPath;
    }

    public void joinPath(JoinPathPlan joinPath) {
        this.joinPath = joinPath;
    }

    public AstGenerationResult astGeneration() {
        return astGeneration;
    }

    public void astGeneration(AstGenerationResult astGeneration) {
        this.astGeneration = astGeneration;
    }

    public AstValidationResult astValidation() {
        return astValidation;
    }

    public CanonicalAst canonicalAst() {
        return canonicalAst;
    }

    public void canonicalAst(CanonicalAst canonicalAst) {
        this.canonicalAst = canonicalAst;
    }

    public void astValidation(AstValidationResult astValidation) {
        this.astValidation = astValidation;
    }

    public CompiledSql compiledSql() {
        return compiledSql;
    }

    public void compiledSql(CompiledSql compiledSql) {
        this.compiledSql = compiledSql;
    }

    public SemanticExecutionResult executionResult() {
        return executionResult;
    }

    public void executionResult(SemanticExecutionResult executionResult) {
        this.executionResult = executionResult;
    }

    public String summary() {
        return summary;
    }

    public void summary(String summary) {
        this.summary = summary;
    }

    public boolean clarificationRequired() {
        return clarificationRequired;
    }

    public void clarificationRequired(boolean clarificationRequired) {
        this.clarificationRequired = clarificationRequired;
    }

    public String clarificationQuestion() {
        return clarificationQuestion;
    }

    public void clarificationQuestion(String clarificationQuestion) {
        this.clarificationQuestion = clarificationQuestion;
    }

    public String clarificationReason() {
        return clarificationReason;
    }

    public void clarificationReason(String clarificationReason) {
        this.clarificationReason = clarificationReason;
    }

    public Double clarificationConfidence() {
        return clarificationConfidence;
    }

    public void clarificationConfidence(Double clarificationConfidence) {
        this.clarificationConfidence = clarificationConfidence;
    }

    public Map<String, Object> clarificationDiagnostics() {
        return clarificationDiagnostics;
    }

    public void clarificationDiagnostics(Map<String, Object> clarificationDiagnostics) {
        this.clarificationDiagnostics = clarificationDiagnostics;
    }
}
