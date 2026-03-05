package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core.SemanticQueryStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.pipeline.SemanticStagePipeline;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.pipeline.SemanticStagePipelineFactory;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SemanticQueryRuntimeService {

    private final ConvEngineMcpConfig mcpConfig;
    private final SemanticModelRegistry modelRegistry;
    private final SemanticStagePipelineFactory stagePipelineFactory;
    private final ObjectProvider<List<SemanticQueryStageInterceptor>> stageInterceptorsProvider;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;

    public Map<String, Object> plan(String question, EngineSession session) {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        if (!cfg.isEnabled()) {
            throw new IllegalStateException("Semantic query mode is disabled. Enable convengine.mcp.db.semantic.enabled=true");
        }
        String mode = mcpConfig.getDb() == null || mcpConfig.getDb().getQuery() == null
                ? ""
                : String.valueOf(mcpConfig.getDb().getQuery().getMode());
        if (!"semantic".equalsIgnoreCase(mode)) {
            throw new IllegalStateException("Semantic runtime invoked while convengine.mcp.db.query.mode is not 'semantic'. current=" + mode);
        }

        List<SemanticQueryStageInterceptor> stageInterceptors = stageInterceptorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(stageInterceptors);

        try {
            String safeQuestion = question == null ? "" : question;
            SemanticQueryContext context = new SemanticQueryContext(safeQuestion, session);
            SemanticStagePipeline stagePipeline = stagePipelineFactory.create();
            for (SemanticQueryStage stage : stagePipeline.stages()) {
                if (stage == null || !stage.supports(context)) {
                    continue;
                }
                publishBefore(stageInterceptors, stage.stageCode(), session, context);
                publishStageEvent("ENTER", stage, context, session, null);
                try {
                    stage.execute(context);
                    publishStageMetadata(stage, context, session);
                    publishAfter(stageInterceptors, stage.stageCode(), session, context);
                    publishStageEvent("EXIT", stage, context, session, null);
                } catch (Exception stageError) {
                    publishStageEvent("ERROR", stage, context, session, stageError);
                    throw stageError;
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mode", "semantic");
            out.put("question", safeQuestion);
            out.put("semanticModelVersion", modelRegistry.getModel().version());
            out.put("retrieval", context.retrieval());
            out.put("joinPath", context.joinPath());
            out.put("ast", context.astGeneration() == null ? null : context.astGeneration().ast());
            out.put("astRawJson", context.astGeneration() == null ? null : context.astGeneration().rawJson());
            out.put("astRepaired", context.astGeneration() != null && context.astGeneration().repaired());
            out.put("astValidation", context.astValidation());
            out.put("compiledSql", context.compiledSql() == null ? null : context.compiledSql().sql());
            out.put("compiledSqlParams", context.compiledSql() == null ? null : context.compiledSql().params());
            out.put("execution", context.executionResult());
            out.put("summary", context.summary());
            out.put("_meta", buildMeta(context, "runtime", "COMPLETED"));
            out.put("next", "completed");
            return out;
        } catch (Exception ex) {
            publishRuntimeError(session, ex);
            for (SemanticQueryStageInterceptor interceptor : stageInterceptors) {
                if (interceptor != null && interceptor.supports(session)) {
                    interceptor.onError("runtime", session, ex);
                }
            }
            throw ex;
        }
    }

    private void publishBefore(List<SemanticQueryStageInterceptor> interceptors, String stage, EngineSession session, Object payload) {
        for (SemanticQueryStageInterceptor interceptor : interceptors) {
            if (interceptor != null && interceptor.supports(session)) {
                interceptor.beforeStage(stage, session, payload);
            }
        }
    }

    private void publishAfter(List<SemanticQueryStageInterceptor> interceptors, String stage, EngineSession session, Object payload) {
        for (SemanticQueryStageInterceptor interceptor : interceptors) {
            if (interceptor != null && interceptor.supports(session)) {
                interceptor.afterStage(stage, session, payload);
            }
        }
    }

    private void publishRuntimeError(EngineSession session, Exception error) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", "semantic-query");
        payload.put("errorClass", error == null ? null : error.getClass().getName());
        payload.put("errorMessage", error == null ? null : error.getMessage());
        String determinant = ConvEngineAuditStage.SEMANTIC_RUNTIME_ERROR.name();
        auditService.audit(determinant, conversationId, payload);
        if (verbosePublisher != null) {
            verbosePublisher.publish(
                    session,
                    "SemanticQueryRuntimeService",
                    determinant,
                    null,
                    resolveToolCode(),
                    true,
                    payload
            );
        }
    }

    private void publishStageEvent(String lifecycle,
                                   SemanticQueryStage stage,
                                   SemanticQueryContext context,
                                   EngineSession session,
                                   Exception error) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null || stage == null || context == null) {
            return;
        }
        String determinant = lifecycleDeterminant(lifecycle, stage);
        Map<String, Object> payload = stagePayload(lifecycle, stage, context, error);
        auditService.audit(determinant, conversationId, payload);
        if (verbosePublisher != null) {
            verbosePublisher.publish(
                    session,
                    stage.getClass().getSimpleName(),
                    determinant,
                    null,
                    resolveToolCode(),
                    "ERROR".equalsIgnoreCase(lifecycle),
                    payload
            );
        }
    }

    private void publishStageMetadata(SemanticQueryStage stage,
                                      SemanticQueryContext context,
                                      EngineSession session) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null || stage == null || context == null) {
            return;
        }
        String determinant = ConvEngineAuditStage.SEMANTIC_STAGE_META.name();
        Map<String, Object> payload = stagePayload("META", stage, context, null);
        auditService.audit(determinant, conversationId, payload);
        if (verbosePublisher != null) {
            verbosePublisher.publish(
                    session,
                    stage.getClass().getSimpleName(),
                    determinant,
                    null,
                    resolveToolCode(),
                    false,
                    payload
            );
        }
    }

    private String lifecycleDeterminant(String lifecycle, SemanticQueryStage stage) {
        if ("ENTER".equalsIgnoreCase(lifecycle)) {
            return ConvEngineAuditStage.SEMANTIC_STAGE_ENTER.name();
        }
        if ("EXIT".equalsIgnoreCase(lifecycle)) {
            return ConvEngineAuditStage.SEMANTIC_STAGE_EXIT.name();
        }
        if ("ERROR".equalsIgnoreCase(lifecycle)) {
            return stageErrorDeterminant(stage);
        }
        return "SEMANTIC_STAGE_" + lifecycle;
    }

    private String stageErrorDeterminant(SemanticQueryStage stage) {
        if (stage == null) {
            return "UNKNOWN_STAGE_ERROR";
        }
        String stageCode = stage.stageCode() == null ? "" : stage.stageCode();
        String normalized = stageCode.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_");
        if (normalized.isBlank()) {
            normalized = "UNKNOWN";
        }
        return normalized + "_STAGE_ERROR";
    }

    private Map<String, Object> stagePayload(String lifecycle,
                                             SemanticQueryStage stage,
                                             SemanticQueryContext context,
                                             Exception error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", "semantic-query");
        payload.put("lifecycle", lifecycle);
        payload.put("stageCode", stage.stageCode());
        payload.put("stageClass", stage.getClass().getSimpleName());
        payload.put("question", abbreviate(context.question(), 300));
        payload.put("semanticModelVersion", modelRegistry.getModel().version());

        payload.put("retrievalEntitiesCount",
                context.retrieval() == null || context.retrieval().candidateEntities() == null
                        ? 0 : context.retrieval().candidateEntities().size());
        payload.put("retrievalTablesCount",
                context.retrieval() == null || context.retrieval().candidateTables() == null
                        ? 0 : context.retrieval().candidateTables().size());
        payload.put("joinPathEdgesCount",
                context.joinPath() == null || context.joinPath().edges() == null
                        ? 0 : context.joinPath().edges().size());
        payload.put("joinPathUnresolvedCount",
                context.joinPath() == null || context.joinPath().unresolvedTables() == null
                        ? 0 : context.joinPath().unresolvedTables().size());
        payload.put("astEntity",
                context.astGeneration() == null || context.astGeneration().ast() == null
                        ? null : context.astGeneration().ast().entity());
        payload.put("astSelectCount",
                context.astGeneration() == null || context.astGeneration().ast() == null || context.astGeneration().ast().select() == null
                        ? 0 : context.astGeneration().ast().select().size());
        payload.put("astFilterCount",
                context.astGeneration() == null || context.astGeneration().ast() == null || context.astGeneration().ast().filters() == null
                        ? 0 : context.astGeneration().ast().filters().size());
        payload.put("astValid",
                context.astValidation() == null ? null : context.astValidation().valid());
        payload.put("astErrorCount",
                context.astValidation() == null || context.astValidation().errors() == null
                        ? 0 : context.astValidation().errors().size());
        payload.put("compiledSqlPreview",
                context.compiledSql() == null ? null : abbreviate(context.compiledSql().sql(), 220));
        payload.put("compiledSqlParamCount",
                context.compiledSql() == null || context.compiledSql().params() == null
                        ? 0 : context.compiledSql().params().size());
        payload.put("executionRowCount",
                context.executionResult() == null ? 0 : context.executionResult().rowCount());
        payload.put("summaryPreview", abbreviate(context.summary(), 220));
        payload.put("_meta", buildMeta(context, stage.stageCode(), lifecycle));

        if (error != null) {
            payload.put("errorClass", error.getClass().getName());
            payload.put("errorMessage", error.getMessage());
        }
        return payload;
    }

    private Map<String, Object> buildMeta(SemanticQueryContext context, String stageCode, String lifecycle) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("toolCode", resolveToolCode());
        meta.put("stageCode", stageCode);
        meta.put("lifecycle", lifecycle);
        meta.put("questionLength", context == null || context.question() == null ? 0 : context.question().length());
        meta.put("astPrepared", context != null && context.astGeneration() != null && context.astGeneration().ast() != null);
        meta.put("astValidated", context != null && context.astValidation() != null);
        meta.put("astValid", context != null && context.astValidation() != null && context.astValidation().valid());
        meta.put("sqlCompiled", context != null && context.compiledSql() != null && context.compiledSql().sql() != null && !context.compiledSql().sql().isBlank());
        meta.put("sqlExecuted", context != null && context.executionResult() != null);
        meta.put("summaryPrepared", context != null && context.summary() != null && !context.summary().isBlank());
        return meta;
    }

    private String resolveToolCode() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        if (cfg == null || cfg.getToolCode() == null || cfg.getToolCode().isBlank()) {
            return "db.semantic.query";
        }
        return cfg.getToolCode().trim();
    }

    private String abbreviate(String text, int max) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }
}
