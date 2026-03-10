package com.github.salilvnair.convengine.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.api.dto.SemanticQueryDebugRequest;
import com.github.salilvnair.convengine.api.dto.SemanticQueryDebugResponse;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.factory.EngineSessionFactory;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.AstPlanner;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.llm.SemanticAstGenerator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.SemanticEntityTableRetriever;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.core.SemanticQueryRuntimeService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class SemanticQueryDebugService {

    private final SemanticQueryRuntimeService semanticQueryRuntimeService;
    private final EngineSessionFactory engineSessionFactory;
    private final ObjectProvider<List<SemanticEntityTableRetriever>> retrieversProvider;
    private final ObjectProvider<List<AstPlanner>> plannersProvider;
    private final ObjectProvider<List<SemanticAstGenerator>> generatorsProvider;
    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    public SemanticQueryDebugResponse analyze(SemanticQueryDebugRequest request) {
        return analyze(request, null);
    }

    public SemanticQueryDebugResponse analyze(SemanticQueryDebugRequest request, Consumer<Map<String, Object>> eventConsumer) {
        String question = request == null || request.getQuestion() == null ? "" : request.getQuestion().trim();
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

        emit(eventConsumer, "STARTED", "Debug analysis started.", Map.of("question", question));

        Map<String, Object> runtime = Map.of();
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
            String runtimeError = "";
            try {
                retrieval = resolveRetriever(session).retrieve(question, session);
                emit(eventConsumer, "RETRIEVAL_DONE", "Entity retrieval completed.", asMap(retrieval));
            } catch (Exception ex) {
                runtimeError = messageOrClass(ex);
                emit(eventConsumer, "RETRIEVAL_ERROR", runtimeError, Map.of());
            }

            if (retrieval != null) {
                try {
                    joinPath = resolvePlanner(session).plan(retrieval, session);
                    emit(eventConsumer, "JOIN_PATH_DONE", "Join path planned.", asMap(joinPath));
                } catch (Exception ex) {
                    runtimeError = appendError(runtimeError, messageOrClass(ex));
                    emit(eventConsumer, "JOIN_PATH_ERROR", messageOrClass(ex), Map.of());
                }
            }

            if (retrieval != null && joinPath != null) {
                try {
                    generated = resolveGenerator(session).generate(question, retrieval, joinPath, session);
                    emit(eventConsumer, "AST_DONE", "AST generated.", Map.of(
                            "rawJson", generated == null ? "" : stringValue(generated.rawJson()),
                            "ast", asMap(generated == null ? null : generated.ast())
                    ));
                } catch (Exception ex) {
                    runtimeError = appendError(runtimeError, messageOrClass(ex));
                    emit(eventConsumer, "AST_ERROR", messageOrClass(ex), Map.of());
                }
            }

            Map<String, Object> retrievalMap = asMap(retrieval);
            Map<String, Object> ast = asMap(generated == null ? null : generated.ast());
            List<Map<String, Object>> candidateEntities = asListOfMaps(retrievalMap.get("candidateEntities"));
            String astRawJson = generated == null ? "" : stringValue(generated.rawJson());
            String astVersion = generated == null || generated.ast() == null ? "" : stringValue(generated.ast().astVersion());

            if (generated != null && generated.ast() != null) {
                try {
                    runtime = semanticQueryRuntimeService.plan(question, session);
                    emit(eventConsumer, "RUNTIME_DONE", "Runtime planning completed.", asMap(runtime));
                } catch (Exception ex) {
                    runtimeError = appendError(runtimeError, messageOrClass(ex));
                    emit(eventConsumer, "RUNTIME_ERROR", messageOrClass(ex), Map.of());
                }
            }

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
            if (!runtimeError.isBlank()) {
                analysis.put("runtime_error", runtimeError);
            }

            Map<String, Object> llmDebug = latestAstLlmPayloads(conversationId);
            Map<String, Object> llmInput = asMap(llmDebug.get("input"));
            Map<String, Object> llmOutput = asMap(llmDebug.get("output"));
            Map<String, Object> llmError = asMap(llmDebug.get("error"));

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
                    stringValue(runtime.get("compiledSql")),
                    asMap(runtime.get("compiledSqlParams")),
                    stringValue(runtime.get("summary")),
                    llmInput,
                    llmOutput,
                    llmError,
                    analysis,
                    runtimeError.isBlank()
                            ? "Debug analysis generated."
                            : "Debug analysis generated (runtime stage error captured)."
            );
            emit(eventConsumer, "FINAL", "Debug analysis completed.", asMap(response));
            return response;
        } finally {
            LlmInvocationContext.clear();
        }
    }

    private void emit(Consumer<Map<String, Object>> eventConsumer,
                      String stage,
                      String message,
                      Map<String, Object> payload) {
        if (eventConsumer == null) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("stage", stage);
        event.put("message", message);
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
}
