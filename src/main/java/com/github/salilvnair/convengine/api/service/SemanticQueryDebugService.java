package com.github.salilvnair.convengine.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.api.dto.SemanticQueryDebugRequest;
import com.github.salilvnair.convengine.api.dto.SemanticQueryDebugResponse;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.factory.EngineSessionFactory;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.canonical.CanonicalAst;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.SemanticQueryAstV1;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate.AstValidationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.validate.AstValidator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.version.AstCanonicalizer;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.AstPlanner;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.llm.SemanticAstGenerator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticField;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticIntentExists;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticIntentFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticIntentRule;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticExecutionResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.execute.SemanticSqlExecutor;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.SemanticEntityTableRetriever;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.CompiledSql;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.core.SemanticSqlCompiler;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.summary.SemanticResultSummarizer;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.repo.AuditRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SemanticQueryDebugService {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern ID_LIKE_TOKEN = Pattern.compile("\\b[A-Z0-9_-]*\\d+[A-Z0-9_-]*\\b", Pattern.CASE_INSENSITIVE);
    private static final double FIELD_OWNERSHIP_WEIGHT = 0.45d;
    private static final double FEEDBACK_VECTOR_ENTITY_WEIGHT = 0.40d;

    private final ConvEngineMcpConfig mcpConfig;
    private final SemanticModelRegistry semanticModelRegistry;
    private final AstCanonicalizer astCanonicalizer;
    private final EngineSessionFactory engineSessionFactory;
    private final ObjectProvider<List<SemanticEntityTableRetriever>> retrieversProvider;
    private final ObjectProvider<List<AstPlanner>> plannersProvider;
    private final ObjectProvider<List<SemanticAstGenerator>> generatorsProvider;
    private final ObjectProvider<List<AstValidator>> astValidatorsProvider;
    private final ObjectProvider<List<SemanticSqlCompiler>> sqlCompilersProvider;
    private final ObjectProvider<List<SemanticSqlExecutor>> sqlExecutorsProvider;
    private final ObjectProvider<List<SemanticResultSummarizer>> summarizersProvider;
    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    public SemanticQueryDebugResponse analyze(SemanticQueryDebugRequest request) {
        return analyze(request, null);
    }

    public SemanticQueryDebugResponse analyze(SemanticQueryDebugRequest request, Consumer<Map<String, Object>> eventConsumer) {
        String question = request == null || request.getQuestion() == null ? "" : request.getQuestion().trim();
        boolean includeRetrieval = request == null || request.includeRetrieval();
        boolean includeJsonPath = includeRetrieval && (request == null || request.includeJsonPath());
        boolean includeAst = includeJsonPath && (request == null || request.includeAst());
        boolean includeSqlGeneration = includeAst && (request == null || request.includeSqlGeneration());
        boolean includeSqlExecution = includeSqlGeneration && (request == null || request.includeSqlExecution());
        if (question.isBlank()) {
            return new SemanticQueryDebugResponse(
                    false,
                    question,
                    "",
                    null,
                    "INVALID_INPUT",
                    List.of(),
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    Map.of(),
                    null,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    "Question is required."
            );
        }
        long startMs = System.currentTimeMillis();
        long[] lastEventMs = new long[]{startMs};

        emit(eventConsumer, "STARTED", "Debug analysis started.", Map.of("question", question), startMs, lastEventMs);

        EngineContext context = EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(question)
                .inputParams(Map.of())
                .userInputParams(Map.of())
                .build();
        EngineSession session = engineSessionFactory.open(context);
        UUID conversationId = session.getConversationId();
        LlmInvocationContext.set(conversationId, "SEMANTIC_QUERY_DEBUG", "ANALYZE");

        try {
            RetrievalResult retrieval = null;
            JoinPathPlan joinPath = null;
            AstGenerationResult generated = null;
            CompiledSql compiledSql = null;
            Map<String, Object> compiledSqlParams = Map.of();
            SemanticExecutionResult execution = null;
            String summary = "";
            String runtimeError = "";
            if (includeRetrieval) {
                try {
                    retrieval = resolveRetriever(session).retrieve(question, session);
                    emit(eventConsumer, "RETRIEVAL_DONE", "Entity retrieval completed.", asMap(retrieval), startMs, lastEventMs);
                    String earlySelected = retrieval == null || retrieval.candidateEntities() == null || retrieval.candidateEntities().isEmpty()
                            ? ""
                            : retrieval.candidateEntities().getFirst().name();
                    Map<String, Object> earlyResolution = buildEntityResolutionDiagnostics(question, retrieval, earlySelected);
                    emit(eventConsumer, "RESOLUTION_DIAGNOSTICS", "Entity resolution diagnostics prepared.", earlyResolution, startMs, lastEventMs);
                } catch (Exception ex) {
                    runtimeError = messageOrClass(ex);
                    emit(eventConsumer, "RETRIEVAL_ERROR", runtimeError, errorPayload(ex), startMs, lastEventMs);
                }
            } else {
                emit(eventConsumer, "RETRIEVAL_SKIPPED", "Retrieval skipped by request.", Map.of(), startMs, lastEventMs);
            }

            if (includeJsonPath) {
                if (retrieval != null) {
                    try {
                        joinPath = resolvePlanner(session).plan(retrieval, session);
                        emit(eventConsumer, "JOIN_PATH_DONE", "Join path planned.", asMap(joinPath), startMs, lastEventMs);
                    } catch (Exception ex) {
                        runtimeError = appendError(runtimeError, messageOrClass(ex));
                        emit(eventConsumer, "JOIN_PATH_ERROR", messageOrClass(ex), errorPayload(ex), startMs, lastEventMs);
                    }
                }
            } else {
                emit(eventConsumer, "JOIN_PATH_SKIPPED", "JsonPath/Join path skipped by request.", Map.of(), startMs, lastEventMs);
            }

            if (includeAst) {
                if (retrieval != null && joinPath != null) {
                    try {
                        generated = resolveGenerator(session).generate(question, retrieval, joinPath, session);
                        emit(eventConsumer, "AST_DONE", "AST generated.", Map.of(
                                "rawJson", generated == null ? "" : stringValue(generated.rawJson()),
                                "ast", asMap(generated == null ? null : generated.ast())
                        ), startMs, lastEventMs);
                    } catch (Exception ex) {
                        runtimeError = appendError(runtimeError, messageOrClass(ex));
                        emit(eventConsumer, "AST_ERROR", messageOrClass(ex), errorPayload(ex), startMs, lastEventMs);
                    }
                }
            } else {
                emit(eventConsumer, "AST_SKIPPED", "AST generation skipped by request.", Map.of(), startMs, lastEventMs);
            }

            if (includeSqlGeneration && generated != null && generated.ast() != null) {
                try {
                    SemanticQueryContext debugContext = new SemanticQueryContext(question, session);
                    debugContext.retrieval(retrieval);
                    debugContext.joinPath(joinPath);
                    debugContext.astGeneration(generated);
                    CanonicalAst canonicalAst = astCanonicalizer.fromV1(generated.ast());
                    debugContext.canonicalAst(canonicalAst);

                    AstValidationResult astValidationResult = resolveAstValidator(session)
                            .validate(canonicalAst, semanticModelRegistry.getModel(), joinPath, session);
                    debugContext.astValidation(astValidationResult);
                    if (astValidationResult == null || !astValidationResult.valid()) {
                        throw new IllegalStateException("semantic AST validation failed: " + (astValidationResult == null ? List.of() : astValidationResult.errors()));
                    }

                    compiledSql = resolveSqlCompiler(debugContext).compile(debugContext);
                    debugContext.compiledSql(compiledSql);
                    compiledSqlParams = asMap(compiledSql == null ? Map.of() : compiledSql.params());
                    emit(eventConsumer, "SQL_GENERATION_DONE", "SQL generated.", Map.of(
                            "compiledSql", compiledSql == null ? null : compiledSql.sql(),
                            "compiledSqlParams", compiledSqlParams
                    ), startMs, lastEventMs);

                    if (includeSqlExecution) {
                        execution = resolveSqlExecutor(debugContext).execute(compiledSql, debugContext);
                        debugContext.executionResult(execution);
                        summary = resolveSummarizer(debugContext).summarize(execution, debugContext);
                        debugContext.summary(summary);
                        emit(eventConsumer, "SQL_EXECUTION_DONE", "SQL execution completed.", Map.of(
                                "rowCount", execution == null ? 0 : execution.rowCount(),
                                "summary", summary
                        ), startMs, lastEventMs);
                    } else {
                        emit(eventConsumer, "SQL_EXECUTION_SKIPPED", "SQL execution skipped by request.", Map.of(), startMs, lastEventMs);
                    }
                } catch (Exception ex) {
                    runtimeError = appendError(runtimeError, messageOrClass(ex));
                    emit(eventConsumer, "SQL_GENERATION_ERROR", messageOrClass(ex), errorPayload(ex), startMs, lastEventMs);
                }
            } else if (!includeSqlGeneration) {
                emit(eventConsumer, "SQL_GENERATION_SKIPPED", "SQL generation skipped by request.", Map.of(), startMs, lastEventMs);
                emit(eventConsumer, "SQL_EXECUTION_SKIPPED", "SQL execution skipped by request.", Map.of(), startMs, lastEventMs);
            }

            Map<String, Object> retrievalMap = asMap(retrieval);
            Map<String, Object> ast = asMap(generated == null ? null : generated.ast());
            List<Map<String, Object>> candidateEntities = asListOfMaps(retrievalMap.get("candidateEntities"));
            String astRawJson = generated == null ? "" : stringValue(generated.rawJson());
            String astVersion = generated == null || generated.ast() == null ? "" : stringValue(generated.ast().astVersion());

            String selectedFromRetrieval = firstName(candidateEntities);
            String astEntity = stringValue(ast.get("entity"));
            String selectedEntity = !astEntity.isBlank() ? astEntity : selectedFromRetrieval;
            boolean astRepaired = generated != null && generated.repaired();
            String reason = resolveReason(selectedFromRetrieval, astEntity, astRepaired);

            Map<String, Object> analysis = new LinkedHashMap<>();
            analysis.put("selected_from_retrieval", selectedFromRetrieval);
            analysis.put("selected_from_ast", astEntity);
            analysis.put("ast_repaired", astRepaired);
            analysis.put("candidate_entity_count", candidateEntities.size());
            analysis.put("retrieval_confidence", retrieval == null ? "" : stringValue(retrieval.confidence()));
            analysis.put("include_retrieval", includeRetrieval);
            analysis.put("include_json_path", includeJsonPath);
            analysis.put("include_ast", includeAst);
            analysis.put("include_sql_generation", includeSqlGeneration);
            analysis.put("include_sql_execution", includeSqlExecution);
            Map<String, Object> entityResolution = buildEntityResolutionDiagnostics(question, retrieval, selectedEntity);
            analysis.put("entity_resolution", entityResolution);
            analysis.put("selected_entity_context", buildSelectedEntityContext(
                    selectedEntity,
                    selectedFromRetrieval,
                    astEntity,
                    reason,
                    astRepaired,
                    retrieval
            ));
            Map<String, Object> sqlGenerationMatrix = buildSqlGenerationMatrix(selectedEntity);
            analysis.put("sql_generation_matrix", sqlGenerationMatrix);
            emit(eventConsumer, "SQL_GENERATION_MATRIX", "SQL generation matrix prepared.", sqlGenerationMatrix, startMs, lastEventMs);
            if (!runtimeError.isBlank()) {
                analysis.put("runtime_error", runtimeError);
            }

            Map<String, Object> llmDebug = latestAstLlmPayloads(conversationId);
            Map<String, Object> llmInput = asMap(llmDebug.get("input"));
            Map<String, Object> llmOutput = asMap(llmDebug.get("output"));
            Map<String, Object> llmError = asMap(llmDebug.get("error"));
            Map<String, Object> appliedRulesTrace = buildAppliedRulesTrace(
                    question,
                    astRawJson,
                    generated == null ? null : generated.ast(),
                    selectedEntity,
                    llmInput,
                    retrieval,
                    joinPath
            );
            analysis.put("applied_rules_trace", appliedRulesTrace);
            emit(eventConsumer, "APPLIED_RULES_TRACE", "Applied rules trace prepared.", appliedRulesTrace, startMs, lastEventMs);

            SemanticQueryDebugResponse response = new SemanticQueryDebugResponse(
                    runtimeError.isBlank(),
                    question,
                    String.valueOf(conversationId),
                    selectedEntity,
                    reason,
                    candidateEntities,
                    retrievalMap,
                    ast,
                    astVersion,
                    astRawJson,
                    compiledSql == null ? "" : stringValue(compiledSql.sql()),
                    compiledSqlParams,
                    summary,
                    llmInput,
                    llmOutput,
                    llmError,
                    analysis,
                    runtimeError.isBlank()
                            ? "Debug analysis generated."
                            : "Debug analysis generated (runtime stage error captured)."
            );
            emit(eventConsumer, "FINAL", "Debug analysis completed.", asMap(response), startMs, lastEventMs);
            return response;
        } catch (Exception ex) {
            SemanticQueryDebugResponse fallback = new SemanticQueryDebugResponse(
                    false,
                    question,
                    String.valueOf(conversationId),
                    null,
                    "RUNTIME_ERROR",
                    List.of(),
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    Map.of(),
                    null,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of("fatal_error", messageOrClass(ex)),
                    "Debug analysis failed with fatal error."
            );
            Map<String, Object> fatalPayload = new LinkedHashMap<>(asMap(fallback));
            fatalPayload.put("error", errorPayload(ex));
            emit(eventConsumer, "FATAL_ERROR", messageOrClass(ex), fatalPayload, startMs, lastEventMs);
            return fallback;
        } finally {
            LlmInvocationContext.clear();
        }
    }

    private void emit(Consumer<Map<String, Object>> eventConsumer,
                      String stage,
                      String message,
                      Map<String, Object> payload,
                      long startMs,
                      long[] lastEventMs) {
        if (eventConsumer == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = (lastEventMs == null || lastEventMs.length == 0) ? startMs : lastEventMs[0];
        long elapsed = Math.max(0L, now - startMs);
        long delta = Math.max(0L, now - previous);
        if (lastEventMs != null && lastEventMs.length > 0) {
            lastEventMs[0] = now;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("stage", stage);
        event.put("message", message);
        event.put("timestamp", now);
        event.put("elapsedMs", elapsed);
        event.put("deltaMs", delta);
        event.put("payload", payload == null ? Map.of() : payload);
        eventConsumer.accept(event);
    }

    private String messageOrClass(Exception ex) {
        if (ex == null) {
            return "";
        }
        if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
            return ex.getMessage();
        }
        return ex.getClass().getSimpleName();
    }

    private String appendError(String existing, String next) {
        if (next == null || next.isBlank()) {
            return existing == null ? "" : existing;
        }
        if (existing == null || existing.isBlank()) {
            return next;
        }
        return existing + " | " + next;
    }

    private Map<String, Object> errorPayload(Exception ex) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errorClass", ex == null ? null : ex.getClass().getName());
        payload.put("errorMessage", ex == null ? null : ex.getMessage());
        Throwable root = rootCause(ex);
        payload.put("rootCauseClass", root == null ? null : root.getClass().getName());
        payload.put("rootCauseMessage", root == null ? null : root.getMessage());
        payload.put("stackTrace", stackTrace(ex));
        return payload;
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        Throwable next = current == null ? null : current.getCause();
        while (next != null && next != current) {
            current = next;
            next = current.getCause();
        }
        return current;
    }

    private String stackTrace(Throwable error) {
        if (error == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        PrintWriter printer = new PrintWriter(writer);
        error.printStackTrace(printer);
        return writer.toString();
    }

    private Map<String, Object> latestAstLlmPayloads(UUID conversationId) {
        if (conversationId == null) {
            return Map.of();
        }
        List<CeAudit> rows = auditRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        Map<String, Object> out = new LinkedHashMap<>();
        for (CeAudit row : rows) {
            if (row == null || row.getStage() == null) {
                continue;
            }
            String stage = row.getStage().trim().toUpperCase();
            if (!"AST_INPUT".equals(stage) && !"AST_OUTPUT".equals(stage) && !"AST_ERROR".equals(stage)) {
                continue;
            }
            Map<String, Object> payload = asMap(JsonUtil.parseOrNull(row.getPayloadJson()));
            if ("AST_INPUT".equals(stage)) {
                out.put("input", payload);
            } else if ("AST_OUTPUT".equals(stage)) {
                out.put("output", payload);
            } else if ("AST_ERROR".equals(stage)) {
                out.put("error", payload);
            }
        }
        return out;
    }

    private SemanticEntityTableRetriever resolveRetriever(EngineSession session) {
        List<SemanticEntityTableRetriever> retrievers = retrieversProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(retrievers);
        for (SemanticEntityTableRetriever retriever : retrievers) {
            if (retriever != null && retriever.supports(session)) {
                return retriever;
            }
        }
        throw new IllegalStateException("No SemanticEntityTableRetriever available.");
    }

    private AstPlanner resolvePlanner(EngineSession session) {
        List<AstPlanner> planners = plannersProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(planners);
        for (AstPlanner planner : planners) {
            if (planner != null && planner.supports(session)) {
                return planner;
            }
        }
        throw new IllegalStateException("No AstPlanner available.");
    }

    private SemanticAstGenerator resolveGenerator(EngineSession session) {
        List<SemanticAstGenerator> generators = generatorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(generators);
        for (SemanticAstGenerator generator : generators) {
            if (generator != null && generator.supports(session)) {
                return generator;
            }
        }
        throw new IllegalStateException("No SemanticAstGenerator available.");
    }

    private AstValidator resolveAstValidator(EngineSession session) {
        List<AstValidator> validators = astValidatorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(validators);
        for (AstValidator validator : validators) {
            if (validator != null && validator.supports(session)) {
                return validator;
            }
        }
        throw new IllegalStateException("No AstValidator available.");
    }

    private SemanticSqlCompiler resolveSqlCompiler(SemanticQueryContext context) {
        List<SemanticSqlCompiler> compilers = sqlCompilersProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(compilers);
        for (SemanticSqlCompiler compiler : compilers) {
            if (compiler != null && compiler.supports(context)) {
                return compiler;
            }
        }
        throw new IllegalStateException("No SemanticSqlCompiler available.");
    }

    private SemanticSqlExecutor resolveSqlExecutor(SemanticQueryContext context) {
        List<SemanticSqlExecutor> executors = sqlExecutorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(executors);
        for (SemanticSqlExecutor executor : executors) {
            if (executor != null && executor.supports(context)) {
                return executor;
            }
        }
        throw new IllegalStateException("No SemanticSqlExecutor available.");
    }

    private SemanticResultSummarizer resolveSummarizer(SemanticQueryContext context) {
        List<SemanticResultSummarizer> summarizers = summarizersProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(summarizers);
        for (SemanticResultSummarizer summarizer : summarizers) {
            if (summarizer != null && summarizer.supports(context)) {
                return summarizer;
            }
        }
        throw new IllegalStateException("No SemanticResultSummarizer available.");
    }

    private Map<String, Object> buildEntityResolutionDiagnostics(String question, RetrievalResult retrieval, String selectedEntity) {
        ConvEngineMcpConfig.Db.Semantic semantic = mcpConfig.getDb() == null
                ? new ConvEngineMcpConfig.Db.Semantic()
                : mcpConfig.getDb().getSemantic();
        ConvEngineMcpConfig.Db.Semantic.Retrieval rc = semantic.getRetrieval() == null
                ? new ConvEngineMcpConfig.Db.Semantic.Retrieval()
                : semantic.getRetrieval();

        List<Map<String, Object>> candidateRows = new ArrayList<>();
        List<?> candidates = retrieval == null ? List.of() : retrieval.candidateEntities();
        for (Object row : candidates) {
            Map<String, Object> candidate = asMap(row);
            double finalScore = toDouble(candidate.get("score"));
            double deterministic = toDouble(candidate.get("deterministicScore"));
            double vector = toDouble(candidate.get("vectorScore"));
            double detWeighted = round(rc.getDeterministicBlendWeight() * clamp01(deterministic));
            double vectorWeighted = round(rc.getVectorBlendWeight() * clamp01(vector));
            double feedbackWeighted = round(Math.max(0.0d, finalScore - detWeighted - vectorWeighted));
            double feedbackEstimated = FEEDBACK_VECTOR_ENTITY_WEIGHT == 0.0d
                    ? 0.0d
                    : round(clamp01(feedbackWeighted / FEEDBACK_VECTOR_ENTITY_WEIGHT));

            Map<String, Object> breakdown = new LinkedHashMap<>();
            breakdown.put("deterministicScore", deterministic);
            breakdown.put("vectorScore", vector);
            breakdown.put("deterministicWeighted", detWeighted);
            breakdown.put("vectorWeighted", vectorWeighted);
            breakdown.put("feedbackWeightedEstimated", feedbackWeighted);
            breakdown.put("feedbackBoostEstimated", feedbackEstimated);
            breakdown.put("finalScore", finalScore);

            Map<String, Object> rowMap = new LinkedHashMap<>();
            rowMap.put("entity", stringValue(candidate.get("name")));
            rowMap.put("rank", candidateRows.size() + 1);
            rowMap.put("reasons", candidate.get("reasons"));
            rowMap.put("signals", asMap(candidate.get("signalScores")));
            rowMap.put("breakdown", breakdown);
            rowMap.put("formulaEval", "final = (detBlend*deterministicScore) + (vecBlend*vectorScore) + (feedbackBlend*feedbackBoost)");
            candidateRows.add(rowMap);
        }

        String winner = candidateRows.isEmpty() ? "" : stringValue(candidateRows.getFirst().get("entity"));
        double winnerScore = candidateRows.isEmpty() ? 0.0d : toDouble(asMap(candidateRows.getFirst().get("breakdown")).get("finalScore"));
        double secondScore = candidateRows.size() < 2 ? 0.0d : toDouble(asMap(candidateRows.get(1).get("breakdown")).get("finalScore"));

        Map<String, Object> inputSignals = new LinkedHashMap<>();
        Set<String> queryTokens = tokenize(question);
        List<String> idLikeTokens = extractIdLikeTokens(question);
        inputSignals.put("queryTokens", queryTokens);
        inputSignals.put("hasIdLikeToken", !idLikeTokens.isEmpty());
        inputSignals.put("idLikeTokens", idLikeTokens);

        Map<String, Object> weights = new LinkedHashMap<>();
        weights.put("synonymWeight", rc.getSynonymWeight());
        weights.put("fieldWeight", rc.getFieldWeight());
        weights.put("idPatternWeight", rc.getIdPatternWeight());
        weights.put("lexicalWeight", rc.getLexicalWeight());
        weights.put("fieldOwnershipWeight", FIELD_OWNERSHIP_WEIGHT);
        weights.put("deterministicBlendWeight", rc.getDeterministicBlendWeight());
        weights.put("vectorBlendWeight", rc.getVectorBlendWeight());
        weights.put("feedbackBlendWeight", FEEDBACK_VECTOR_ENTITY_WEIGHT);
        weights.put("minEntityScore", rc.getMinEntityScore());

        Map<String, Object> winnerMap = new LinkedHashMap<>();
        winnerMap.put("entity", winner);
        winnerMap.put("winnerScore", round(winnerScore));
        winnerMap.put("secondScore", round(secondScore));
        winnerMap.put("marginVsSecond", round(Math.max(0.0d, winnerScore - secondScore)));
        winnerMap.put("reason", winner.isBlank() ? "NO_CANDIDATE" : "Highest final score after deterministic/vector/feedback blending");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formula", "deterministic = synonymWeight*synonym + fieldWeight*field + idPatternWeight*idPattern + lexicalWeight*lexical + fieldOwnershipWeight*fieldOwnership");
        out.put("finalScoreFormula", "final = deterministicBlendWeight*clamp01(deterministic) + vectorBlendWeight*clamp01(vectorScore) + feedbackBlendWeight*clamp01(feedbackBoost)");
        out.put("weights", weights);
        out.put("inputSignals", inputSignals);
        out.put("candidates", candidateRows);
        out.put("winner", winnerMap);
        out.put("retrievalConfidence", retrieval == null ? "" : retrieval.confidence());
        out.put("selectedEntityContext", buildSelectedEntityContext(selectedEntity, retrieval));
        return out;
    }

    private Map<String, Object> buildSelectedEntityContext(String selectedEntity, RetrievalResult retrieval) {
        return buildSelectedEntityContext(selectedEntity, "", "", "TOP_RETRIEVAL_CANDIDATE", false, retrieval);
    }

    private Map<String, Object> buildSelectedEntityContext(String selectedEntity,
                                                           String selectedFromRetrieval,
                                                           String astEntity,
                                                           String finalReason,
                                                           boolean astRepaired,
                                                           RetrievalResult retrieval) {
        Map<String, Object> out = new LinkedHashMap<>();
        String safeEntity = selectedEntity == null ? "" : selectedEntity.trim();
        out.put("entity", safeEntity);
        Map<String, Object> selectionPath = new LinkedHashMap<>();
        selectionPath.put("retrievalWinner", selectedFromRetrieval == null ? "" : selectedFromRetrieval);
        selectionPath.put("astEntity", astEntity == null ? "" : astEntity);
        selectionPath.put("finalEntity", safeEntity);
        selectionPath.put("reasonCode", finalReason == null ? "" : finalReason);
        selectionPath.put("astRepaired", astRepaired);
        out.put("selectionPath", selectionPath);
        if (safeEntity.isBlank()) {
            out.put("reason", "No selected entity.");
            return out;
        }

        SemanticModel model = semanticModelRegistry.getModel();
        if (model == null || model.entities() == null) {
            out.put("reason", "Semantic model unavailable.");
            return out;
        }
        SemanticEntity entity = model.entities().get(safeEntity);
        if (entity == null) {
            out.put("reason", "Selected entity not found in semantic model.");
            return out;
        }

        out.put("description", entity.description());
        out.put("synonyms", entity.synonyms() == null ? List.of() : entity.synonyms());

        Map<String, Object> tables = new LinkedHashMap<>();
        String primary = entity.tables() == null ? null : entity.tables().primary();
        List<String> related = entity.tables() == null || entity.tables().related() == null ? List.of() : entity.tables().related();
        LinkedHashSet<String> tableSet = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) {
            tableSet.add(primary);
        }
        tableSet.addAll(related);
        tables.put("primary", primary);
        tables.put("related", related);
        tables.put("all", List.copyOf(tableSet));
        out.put("tables", tables);

        List<Map<String, Object>> fieldRows = new ArrayList<>();
        Map<String, List<String>> tableCoverage = new LinkedHashMap<>();
        if (entity.fields() != null) {
            for (Map.Entry<String, SemanticField> e : entity.fields().entrySet()) {
                String fieldName = e.getKey();
                SemanticField field = e.getValue();
                if (field == null) {
                    continue;
                }
                String column = field.column();
                String tableName = extractTable(column);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("field", fieldName);
                row.put("column", column);
                row.put("table", tableName);
                row.put("type", field.type());
                row.put("filterable", Boolean.TRUE.equals(field.filterable()));
                row.put("searchable", Boolean.TRUE.equals(field.searchable()));
                row.put("key", Boolean.TRUE.equals(field.key()));
                row.put("aliases", field.aliases() == null ? List.of() : field.aliases());
                row.put("allowedValues", field.allowedValues() == null ? List.of() : field.allowedValues());
                row.put("description", field.description());
                fieldRows.add(row);
                if (!tableName.isBlank()) {
                    tableCoverage.computeIfAbsent(tableName, k -> new ArrayList<>()).add(fieldName);
                }
            }
        }
        out.put("fields", fieldRows);
        out.put("fieldCount", fieldRows.size());
        out.put("tableCoverage", tableCoverage);

        Map<String, Object> selectionEvidence = new LinkedHashMap<>();
        if (retrieval != null && retrieval.candidateEntities() != null) {
            retrieval.candidateEntities().stream()
                    .filter(c -> safeEntity.equals(c.name()))
                    .findFirst()
                    .ifPresent(c -> {
                        selectionEvidence.put("evidenceSource", "selected_entity_candidate");
                        selectionEvidence.put("score", c.score());
                        selectionEvidence.put("deterministicScore", c.deterministicScore());
                        selectionEvidence.put("vectorScore", c.vectorScore());
                        selectionEvidence.put("reasons", c.reasons() == null ? List.of() : c.reasons());
                        selectionEvidence.put("signals", c.signalScores() == null ? Map.of() : c.signalScores());
                    });
            if (selectionEvidence.isEmpty() && !retrieval.candidateEntities().isEmpty()) {
                var winner = retrieval.candidateEntities().getFirst();
                selectionEvidence.put("evidenceSource", "retrieval_winner_fallback");
                selectionEvidence.put("winnerEntity", winner.name());
                selectionEvidence.put("score", winner.score());
                selectionEvidence.put("deterministicScore", winner.deterministicScore());
                selectionEvidence.put("vectorScore", winner.vectorScore());
                selectionEvidence.put("reasons", winner.reasons() == null ? List.of() : winner.reasons());
                selectionEvidence.put("signals", winner.signalScores() == null ? Map.of() : winner.signalScores());
                selectionEvidence.put("note", "Selected entity differs from retrieval winner (likely AST override/repair).");
            }
        }
        out.put("selectionEvidence", selectionEvidence);
        out.put("whyNarrative", buildWhyNarrative(finalReason, selectedFromRetrieval, astEntity, safeEntity, astRepaired, selectionEvidence));
        return out;
    }

    private String buildWhyNarrative(String finalReason,
                                     String selectedFromRetrieval,
                                     String astEntity,
                                     String selectedEntity,
                                     boolean astRepaired,
                                     Map<String, Object> evidence) {
        String reason = finalReason == null ? "" : finalReason.trim();
        String retrieval = selectedFromRetrieval == null ? "" : selectedFromRetrieval.trim();
        String ast = astEntity == null ? "" : astEntity.trim();
        String selected = selectedEntity == null ? "" : selectedEntity.trim();
        if ("TOP_RETRIEVAL_CANDIDATE".equals(reason)) {
            return "Final entity matches top retrieval winner (" + selected + ").";
        }
        if ("AST_ENTITY_OVERRIDE".equals(reason)) {
            return "Final entity came from AST override (" + ast + "), while retrieval winner was " + retrieval + ".";
        }
        if ("AST_REPAIR_ENTITY_SWITCH".equals(reason)) {
            return "AST repair switched entity to " + selected + " after validation.";
        }
        if ("NO_ENTITY_SELECTED".equals(reason)) {
            return "No entity could be selected from retrieval or AST.";
        }
        if (astRepaired) {
            return "Entity chosen after AST repair flow: " + selected + ".";
        }
        if (evidence != null && !evidence.isEmpty()) {
            return "Entity selected using retrieval/AST signals and scoring evidence.";
        }
        return "Entity selected by semantic planning flow.";
    }

    private String extractTable(String tableDotColumn) {
        if (tableDotColumn == null || tableDotColumn.isBlank()) {
            return "";
        }
        int dot = tableDotColumn.indexOf('.');
        if (dot <= 0) {
            return "";
        }
        return tableDotColumn.substring(0, dot);
    }

    private List<String> extractIdLikeTokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        var matcher = ID_LIKE_TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (token != null && !token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String[] parts = TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT));
        Set<String> out = new LinkedHashSet<>();
        for (String part : parts) {
            if (!part.isBlank() && part.length() > 1) {
                out.add(part);
            }
        }
        return out;
    }

    private double clamp01(double v) {
        if (v < 0.0d) {
            return 0.0d;
        }
        if (v > 1.0d) {
            return 1.0d;
        }
        return v;
    }

    private double round(double v) {
        return Math.round(v * 1000.0d) / 1000.0d;
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return 0.0d;
        }
    }

    private String resolveReason(String selectedFromRetrieval, String astEntity, boolean astRepaired) {
        if (selectedFromRetrieval.isBlank() && astEntity.isBlank()) {
            return "NO_ENTITY_SELECTED";
        }
        if (astRepaired) {
            return "AST_REPAIR_ENTITY_SWITCH";
        }
        if (!selectedFromRetrieval.isBlank() && !astEntity.isBlank() && !selectedFromRetrieval.equals(astEntity)) {
            return "AST_ENTITY_OVERRIDE";
        }
        return "TOP_RETRIEVAL_CANDIDATE";
    }

    private String firstName(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        return stringValue(rows.getFirst().get("name"));
    }

    private Map<String, Object> asMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return objectMapper.convertValue(value, new TypeReference<>() {});
    }

    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object row : list) {
                out.add(asMap(row));
            }
            return out;
        }
        return List.of();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }

    private Map<String, Object> buildSqlGenerationMatrix(String selectedEntity) {
        Map<String, Object> out = new LinkedHashMap<>();
        SemanticModel model = semanticModelRegistry.getModel();
        if (model == null) {
            out.put("note", "Semantic model unavailable.");
            return out;
        }

        SemanticEntity entity = model.entities() == null ? null : model.entities().get(selectedEntity);
        Set<String> entityFieldNames = entity == null || entity.fields() == null ? Set.of() : entity.fields().keySet();
        Set<String> entityTables = new LinkedHashSet<>();
        if (entity != null && entity.tables() != null) {
            if (entity.tables().primary() != null && !entity.tables().primary().isBlank()) {
                entityTables.add(entity.tables().primary());
            }
            if (entity.tables().related() != null) {
                entityTables.addAll(entity.tables().related().stream().filter(Objects::nonNull).toList());
            }
        }

        Map<String, Object> global = new LinkedHashMap<>();
        Map<String, Object> scoped = new LinkedHashMap<>();
        Map<String, Object> entitySpecific = new LinkedHashMap<>();

        List<Map<String, Object>> intentRulesGlobal = new ArrayList<>();
        if (model.intentRules() != null) {
            model.intentRules().forEach((name, rule) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("description", rule == null ? "" : rule.description());
                row.put("force_entity", rule == null ? "" : rule.forceEntity());
                row.put("force_mode", rule == null ? "" : rule.forceMode());
                row.put("force_select", rule == null || rule.forceSelect() == null ? List.of() : rule.forceSelect());
                row.put("enforce_where", rule == null ? List.of() : toIntentFiltersPayload(rule.enforceWhere()));
                row.put("enforce_exists", rule == null ? List.of() : toIntentExistsPayload(rule.enforceExists()));
                intentRulesGlobal.add(row);
            });
        }
        global.put("intent_rules", intentRulesGlobal);
        scoped.put("intent_rules", intentRulesGlobal.stream()
                .filter(row -> {
                    String forced = stringValue(row.get("force_entity"));
                    return forced.isBlank() || forced.equals(selectedEntity);
                })
                .toList());
        entitySpecific.put("intent_rules", List.of());

        List<Map<String, Object>> valuePatternsGlobal = new ArrayList<>();
        if (model.valuePatterns() != null) {
            model.valuePatterns().forEach(vp -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("from_field", vp.fromField());
                row.put("to_field", vp.toField());
                row.put("value_starts_with", vp.valueStartsWith() == null ? List.of() : vp.valueStartsWith());
                valuePatternsGlobal.add(row);
            });
        }
        global.put("value_patterns", valuePatternsGlobal);
        scoped.put("value_patterns", valuePatternsGlobal.stream()
                .filter(row -> entityFieldNames.contains(stringValue(row.get("from_field")))
                        || entityFieldNames.contains(stringValue(row.get("to_field"))))
                .toList());
        entitySpecific.put("value_patterns", List.of());

        List<Map<String, Object>> metricsGlobal = new ArrayList<>();
        if (model.metrics() != null) {
            model.metrics().forEach((name, metric) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("description", metric == null ? "" : metric.description());
                row.put("sql", metric == null ? "" : metric.sql());
                metricsGlobal.add(row);
            });
        }
        global.put("metrics", metricsGlobal);
        scoped.put("metrics", metricsGlobal.stream()
                .filter(row -> {
                    String sql = stringValue(row.get("sql")).toLowerCase(Locale.ROOT);
                    return entityTables.stream().anyMatch(t -> sql.contains(String.valueOf(t).toLowerCase(Locale.ROOT)));
                })
                .toList());
        entitySpecific.put("metrics", List.of());

        Map<String, List<String>> joinHintsGlobal = model.joinHints() == null ? Map.of() : model.joinHints().entrySet().stream()
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue() == null || e.getValue().commonlyJoinedWith() == null ? List.of() : e.getValue().commonlyJoinedWith()), Map::putAll);
        global.put("join_hints", joinHintsGlobal);
        Map<String, List<String>> joinHintsScoped = new LinkedHashMap<>();
        joinHintsGlobal.forEach((table, joins) -> {
            if (entityTables.contains(table)) {
                joinHintsScoped.put(table, joins);
            }
        });
        scoped.put("join_hints", joinHintsScoped);
        entitySpecific.put("join_hints", Map.of());

        Map<String, List<String>> synonymsGlobal = model.synonyms() == null ? Map.of() : new LinkedHashMap<>(model.synonyms());
        global.put("synonyms", synonymsGlobal);
        scoped.put("synonyms", synonymsGlobal);
        Map<String, List<String>> entitySynonyms = new LinkedHashMap<>();
        if (entity != null && entity.synonyms() != null && !entity.synonyms().isEmpty()) {
            entitySynonyms.put(selectedEntity == null ? "" : selectedEntity, entity.synonyms());
        }
        entitySpecific.put("synonyms", entitySynonyms);

        List<Map<String, Object>> relationshipsGlobal = new ArrayList<>();
        if (model.relationships() != null) {
            model.relationships().forEach(rel -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", rel == null ? "" : rel.name());
                row.put("type", rel == null ? "" : rel.type());
                Map<String, Object> from = new LinkedHashMap<>();
                from.put("table", rel == null || rel.from() == null ? "" : rel.from().table());
                from.put("column", rel == null || rel.from() == null ? "" : rel.from().column());
                Map<String, Object> to = new LinkedHashMap<>();
                to.put("table", rel == null || rel.to() == null ? "" : rel.to().table());
                to.put("column", rel == null || rel.to() == null ? "" : rel.to().column());
                row.put("from", from);
                row.put("to", to);
                relationshipsGlobal.add(row);
            });
        }
        global.put("relationships", relationshipsGlobal);
        scoped.put("relationships", relationshipsGlobal.stream()
                .filter(row -> {
                    Map<String, Object> from = asMap(row.get("from"));
                    Map<String, Object> to = asMap(row.get("to"));
                    return entityTables.contains(stringValue(from.get("table")))
                            || entityTables.contains(stringValue(to.get("table")));
                })
                .toList());
        entitySpecific.put("relationships", List.of());

        Map<String, Object> rulesGlobal = new LinkedHashMap<>();
        if (model.rules() != null) {
            rulesGlobal.put("allowed_tables", model.rules().allowedTables() == null ? List.of() : model.rules().allowedTables());
            rulesGlobal.put("deny_operations", model.rules().denyOperations() == null ? List.of() : model.rules().denyOperations());
            rulesGlobal.put("max_result_limit", model.rules().maxResultLimit());
        }
        global.put("rules", rulesGlobal);
        entitySpecific.put("rules", Map.of());
        out.put("selected_entity", selectedEntity == null ? "" : selectedEntity);
        out.put("entity_tables", List.copyOf(entityTables));
        out.put("global", global);
        out.put("scoped", scoped);
        out.put("entity_specific", entitySpecific);
        return out;
    }

    private List<Map<String, Object>> resolveMatchedIntentRules(String question, SemanticModel model, int maxRules) {
        if (question == null || question.isBlank() || model == null || model.intentRules() == null || model.intentRules().isEmpty()) {
            return List.of();
        }
        String q = question.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matches = new ArrayList<>();
        model.intentRules().forEach((ruleName, rule) -> {
            if (rule == null) {
                return;
            }
            if (!containsAll(q, rule.mustContain())) {
                return;
            }
            int score = matchScore(q, rule.matchAny());
            if (score <= 0) {
                return;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", ruleName);
            row.put("description", rule.description());
            row.put("force_entity", rule.forceEntity());
            row.put("force_mode", rule.forceMode());
            row.put("force_select", rule.forceSelect() == null ? List.of() : rule.forceSelect());
            row.put("enforce_where", toIntentFiltersPayload(rule.enforceWhere()));
            row.put("enforce_exists", toIntentExistsPayload(rule.enforceExists()));
            row.put("_score", score);
            matches.add(row);
        });
        matches.sort((a, b) -> Integer.compare(((Number) b.get("_score")).intValue(), ((Number) a.get("_score")).intValue()));
        List<Map<String, Object>> limited = matches;
        if (matches.size() > maxRules) {
            limited = new ArrayList<>(matches.subList(0, maxRules));
        }
        for (Map<String, Object> row : limited) {
            row.remove("_score");
        }
        return limited;
    }

    private boolean containsAll(String questionLower, List<String> requiredTokens) {
        if (requiredTokens == null || requiredTokens.isEmpty()) {
            return true;
        }
        for (String token : requiredTokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (!questionLower.contains(token.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private int matchScore(String questionLower, List<String> matchAny) {
        if (matchAny == null || matchAny.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String token : matchAny) {
            if (token != null && !token.isBlank() && questionLower.contains(token.toLowerCase(Locale.ROOT))) {
                score++;
            }
        }
        return score;
    }

    private Map<String, Object> buildAppliedRulesTrace(String question,
                                                       String astRawJson,
                                                       SemanticQueryAstV1 normalizedAst,
                                                       String selectedEntity,
                                                       Map<String, Object> llmInput,
                                                       RetrievalResult retrieval,
                                                       JoinPathPlan joinPath) {
        Map<String, Object> out = new LinkedHashMap<>();
        SemanticModel model = semanticModelRegistry.getModel();
        if (model == null) {
            out.put("note", "Semantic model unavailable.");
            return out;
        }

        List<Map<String, Object>> matchedRules = resolveMatchedIntentRules(question, model, 5);
        out.put("matched_intent_rules", matchedRules);

        Map.Entry<String, SemanticIntentRule> selectedRuleEntry = resolveSelectedIntentRuleEntry(question, model);
        Map<String, Object> selectedRule = new LinkedHashMap<>();
        if (selectedRuleEntry != null && selectedRuleEntry.getValue() != null) {
            SemanticIntentRule rule = selectedRuleEntry.getValue();
            selectedRule.put("name", selectedRuleEntry.getKey());
            selectedRule.put("description", rule.description());
            selectedRule.put("force_entity", rule.forceEntity());
            selectedRule.put("force_mode", rule.forceMode());
            selectedRule.put("force_select", rule.forceSelect() == null ? List.of() : rule.forceSelect());
            selectedRule.put("enforce_where", toIntentFiltersPayload(rule.enforceWhere()));
            selectedRule.put("enforce_exists", toIntentExistsPayload(rule.enforceExists()));
            selectedRule.put("applied_at_stage", "AST_NORMALIZATION");
        } else {
            selectedRule.put("name", "");
            selectedRule.put("note", "No intent rule matched.");
        }
        out.put("selected_intent_rule", selectedRule);

        Map<String, Object> remapTrace = buildValuePatternRemapTrace(astRawJson, normalizedAst, model, selectedEntity);
        out.put("value_pattern_remaps", remapTrace);
        out.put("prompt_provenance", buildPromptProvenance(llmInput));
        out.put("normalization_diff", buildNormalizationDiff(astRawJson, normalizedAst));
        out.put("deduction_chain", buildDeductionChain(question, selectedEntity, retrieval, joinPath, selectedRuleEntry));

        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(stageRow("AST_INPUT", "Matched intent rules were provided to LLM prompt context."));
        stages.add(stageRow("AST_NORMALIZATION", "Intent-rule enforce_where/enforce_exists injected after LLM AST parse."));
        stages.add(stageRow("AST_NORMALIZATION", "Value-pattern field remaps applied after alias normalization."));
        stages.add(stageRow("AST_VALIDATION", "Contradictions/invalid filters validated before SQL compile."));
        out.put("stages", stages);

        return out;
    }

    private Map<String, Object> buildPromptProvenance(Map<String, Object> llmInput) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (llmInput == null || llmInput.isEmpty()) {
            out.put("available", false);
            out.put("note", "No AST_INPUT payload available.");
            return out;
        }
        String systemPrompt = stringValue(llmInput.get("systemPrompt"));
        String userPrompt = stringValue(llmInput.get("userPrompt"));
        String contextPreview = stringValue(llmInput.get("contextPreview"));
        out.put("available", true);
        out.put("attempt", llmInput.getOrDefault("attempt", 1));
        out.put("system_prompt_chars", systemPrompt.length());
        out.put("user_prompt_chars", userPrompt.length());
        out.put("context_preview_chars", contextPreview.length());
        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(sectionRow("allowed_fields", userPrompt.contains("Allowed fields for selected entity")));
        sections.add(sectionRow("allowed_values", userPrompt.contains("Allowed values by field")));
        sections.add(sectionRow("supported_operators", userPrompt.contains("Supported filter operators")));
        sections.add(sectionRow("relevant_metrics", userPrompt.contains("Relevant metrics")));
        sections.add(sectionRow("matched_intent_rules", userPrompt.contains("Matched intent rules")));
        sections.add(sectionRow("value_patterns", userPrompt.contains("Relevant value patterns")));
        sections.add(sectionRow("relationships", userPrompt.contains("Relevant relationships")));
        sections.add(sectionRow("join_hints", userPrompt.contains("Relevant join hints")));
        sections.add(sectionRow("synonyms", userPrompt.contains("Relevant synonyms")));
        sections.add(sectionRow("rules", userPrompt.contains("Relevant rules")));
        sections.add(sectionRow("candidate_entities", userPrompt.contains("Candidate entities")));
        sections.add(sectionRow("candidate_tables", userPrompt.contains("Candidate tables")));
        sections.add(sectionRow("join_path", userPrompt.contains("Join path")));
        out.put("prompt_sections", sections);
        return out;
    }

    private Map<String, Object> sectionRow(String name, boolean included) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("section", name);
        row.put("included", included);
        row.put("status", included ? "INCLUDED" : "MISSING");
        return row;
    }

    private Map<String, Object> buildNormalizationDiff(String astRawJson, SemanticQueryAstV1 normalizedAst) {
        Map<String, Object> out = new LinkedHashMap<>();
        SemanticQueryAstV1 rawAst = null;
        try {
            if (astRawJson != null && !astRawJson.isBlank()) {
                rawAst = objectMapper.readValue(astRawJson, SemanticQueryAstV1.class);
            }
        } catch (Exception ignore) {
        }
        if (rawAst == null || normalizedAst == null) {
            out.put("available", false);
            out.put("note", "Raw/normalized AST comparison unavailable.");
            return out;
        }
        out.put("available", true);
        out.put("entity_before", rawAst.entity());
        out.put("entity_after", normalizedAst.entity());
        out.put("entity_changed", !stringValue(rawAst.entity()).equals(stringValue(normalizedAst.entity())));
        out.put("select_count_before", rawAst.select() == null ? 0 : rawAst.select().size());
        out.put("select_count_after", normalizedAst.select() == null ? 0 : normalizedAst.select().size());
        out.put("filter_count_before", flattenFilters(rawAst.filters(), rawAst.where()).size());
        out.put("filter_count_after", flattenFilters(normalizedAst.filters(), normalizedAst.where()).size());
        out.put("exists_count_before", rawAst.existsBlocks() == null ? 0 : rawAst.existsBlocks().size());
        out.put("exists_count_after", normalizedAst.existsBlocks() == null ? 0 : normalizedAst.existsBlocks().size());
        return out;
    }

    private List<Map<String, Object>> buildDeductionChain(String question,
                                                          String selectedEntity,
                                                          RetrievalResult retrieval,
                                                          JoinPathPlan joinPath,
                                                          Map.Entry<String, SemanticIntentRule> selectedRuleEntry) {
        List<Map<String, Object>> chain = new ArrayList<>();
        chain.add(deductionStep("Q1", "Question parsed", Map.of("question", question == null ? "" : question)));
        chain.add(deductionStep("Q2", "Retrieval scored candidate entities/tables", Map.of(
                "candidate_entity_count", retrieval == null || retrieval.candidateEntities() == null ? 0 : retrieval.candidateEntities().size(),
                "candidate_table_count", retrieval == null || retrieval.candidateTables() == null ? 0 : retrieval.candidateTables().size(),
                "retrieval_confidence", retrieval == null ? "" : stringValue(retrieval.confidence())
        )));
        chain.add(deductionStep("Q3", "Join path planned", Map.of(
                "base_table", joinPath == null ? "" : stringValue(joinPath.baseTable()),
                "required_tables", joinPath == null ? List.of() : joinPath.requiredTables(),
                "unresolved_tables", joinPath == null ? List.of() : joinPath.unresolvedTables()
        )));
        chain.add(deductionStep("Q4", "Intent-rule match applied", Map.of(
                "matched_rule", selectedRuleEntry == null ? "" : selectedRuleEntry.getKey(),
                "force_entity", selectedRuleEntry == null || selectedRuleEntry.getValue() == null ? "" : stringValue(selectedRuleEntry.getValue().forceEntity()),
                "force_mode", selectedRuleEntry == null || selectedRuleEntry.getValue() == null ? "" : stringValue(selectedRuleEntry.getValue().forceMode())
        )));
        chain.add(deductionStep("Q5", "Final selected entity for AST", Map.of("selected_entity", selectedEntity == null ? "" : selectedEntity)));
        return chain;
    }

    private Map<String, Object> deductionStep(String id, String title, Map<String, Object> evidence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("title", title);
        row.put("evidence", evidence == null ? Map.of() : evidence);
        return row;
    }

    private Map<String, Object> stageRow(String stage, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("detail", detail);
        return row;
    }

    private Map.Entry<String, SemanticIntentRule> resolveSelectedIntentRuleEntry(String question, SemanticModel model) {
        if (question == null || question.isBlank() || model == null || model.intentRules() == null || model.intentRules().isEmpty()) {
            return null;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, SemanticIntentRule> entry : model.intentRules().entrySet()) {
            SemanticIntentRule rule = entry.getValue();
            if (rule == null) {
                continue;
            }
            if (!containsAll(lower, rule.mustContain())) {
                continue;
            }
            int score = matchScore(lower, rule.matchAny());
            if (score > 0) {
                return entry;
            }
        }
        return null;
    }

    private List<Map<String, Object>> toIntentFiltersPayload(List<SemanticIntentFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SemanticIntentFilter filter : filters) {
            if (filter == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", filter.field());
            row.put("op", filter.op());
            row.put("value", filter.value());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> toIntentExistsPayload(List<SemanticIntentExists> existsList) {
        if (existsList == null || existsList.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SemanticIntentExists exists : existsList) {
            if (exists == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", exists.entity());
            row.put("not_exists", Boolean.TRUE.equals(exists.notExists()));
            row.put("where", toIntentFiltersPayload(exists.where()));
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> buildValuePatternRemapTrace(String astRawJson,
                                                            SemanticQueryAstV1 normalizedAst,
                                                            SemanticModel model,
                                                            String selectedEntity) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (model == null || model.valuePatterns() == null || model.valuePatterns().isEmpty()) {
            out.put("considered", List.of());
            out.put("applied", List.of());
            out.put("applied_at_stage", "AST_NORMALIZATION");
            return out;
        }
        SemanticQueryAstV1 rawAst = null;
        try {
            if (astRawJson != null && !astRawJson.isBlank()) {
                rawAst = objectMapper.readValue(astRawJson, SemanticQueryAstV1.class);
            }
        } catch (Exception ignore) {
        }

        List<Map<String, Object>> considered = new ArrayList<>();
        List<Map<String, Object>> applied = new ArrayList<>();
        for (var vp : model.valuePatterns()) {
            if (vp == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from_field", vp.fromField());
            item.put("to_field", vp.toField());
            item.put("value_starts_with", vp.valueStartsWith() == null ? List.of() : vp.valueStartsWith());
            item.put("selected_entity_related", isValuePatternRelatedToSelectedEntity(model, selectedEntity, vp.fromField(), vp.toField()));
            considered.add(item);

            Map<String, Object> appliedRow = detectAppliedRemap(rawAst, normalizedAst, vp.fromField(), vp.toField());
            if (!appliedRow.isEmpty()) {
                applied.add(appliedRow);
            }
        }
        out.put("considered", considered);
        out.put("applied", applied);
        out.put("applied_at_stage", "AST_NORMALIZATION");
        return out;
    }

    private boolean isValuePatternRelatedToSelectedEntity(SemanticModel model,
                                                          String selectedEntity,
                                                          String fromField,
                                                          String toField) {
        if (model == null || model.entities() == null || selectedEntity == null || selectedEntity.isBlank()) {
            return false;
        }
        SemanticEntity entity = model.entities().get(selectedEntity);
        if (entity == null || entity.fields() == null) {
            return false;
        }
        return entity.fields().containsKey(fromField) || entity.fields().containsKey(toField);
    }

    private Map<String, Object> detectAppliedRemap(SemanticQueryAstV1 rawAst,
                                                   SemanticQueryAstV1 normalizedAst,
                                                   String fromField,
                                                   String toField) {
        if (rawAst == null || normalizedAst == null || fromField == null || toField == null) {
            return Map.of();
        }
        List<AstFilter> rawFilters = flattenFilters(rawAst.filters(), rawAst.where());
        List<AstFilter> normalizedFilters = flattenFilters(normalizedAst.filters(), normalizedAst.where());
        for (AstFilter raw : rawFilters) {
            if (raw == null || !fromField.equals(raw.field())) {
                continue;
            }
            for (AstFilter normalized : normalizedFilters) {
                if (normalized == null || !toField.equals(normalized.field())) {
                    continue;
                }
                boolean sameOp = safeOp(raw).equals(safeOp(normalized));
                boolean sameValue = String.valueOf(raw.value()).equals(String.valueOf(normalized.value()));
                if (!sameOp || !sameValue) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("from_field", fromField);
                row.put("to_field", toField);
                row.put("op", safeOp(normalized));
                row.put("value", normalized.value());
                row.put("applied", true);
                return row;
            }
        }
        return Map.of();
    }

    private String safeOp(AstFilter filter) {
        if (filter == null) {
            return "";
        }
        if (filter.op() != null && !filter.op().isBlank()) {
            return filter.op().trim().toUpperCase(Locale.ROOT);
        }
        return filter.operatorEnum().name();
    }

    private List<AstFilter> flattenFilters(List<AstFilter> flat, AstFilterGroup where) {
        List<AstFilter> out = new ArrayList<>();
        if (flat != null) {
            out.addAll(flat.stream().filter(f -> f != null && f.field() != null).toList());
        }
        collectGroupFilters(where, out);
        return out;
    }

    private void collectGroupFilters(AstFilterGroup group, List<AstFilter> out) {
        if (group == null || out == null) {
            return;
        }
        if (group.conditions() != null) {
            out.addAll(group.conditions().stream().filter(f -> f != null && f.field() != null).toList());
        }
        if (group.groups() != null) {
            for (AstFilterGroup child : group.groups()) {
                collectGroupFilters(child, out);
            }
        }
    }
}
