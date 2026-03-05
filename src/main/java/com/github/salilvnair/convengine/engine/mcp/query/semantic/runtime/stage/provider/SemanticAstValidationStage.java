package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.AstValidationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.AstValidator;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core.SemanticQueryStage;
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
@MustRunAfter(SemanticAstGenerationStage.class)
@RequiredArgsConstructor
public class SemanticAstValidationStage implements SemanticQueryStage {

    private final SemanticModelRegistry modelRegistry;
    private final ObjectProvider<List<AstValidator>> validatorsProvider;
    private final ConvEngineMcpConfig mcpConfig;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;

    @Override
    public String stageCode() {
        return "ast-validation";
    }

    @Override
    public void execute(SemanticQueryContext context) {
        if (context.astGeneration() == null || context.astGeneration().ast() == null) {
            throw new IllegalStateException("ast-generation must complete before ast-validation stage");
        }
        AstValidator validator = resolveValidator(context);
        SemanticModel model = modelRegistry.getModel();
        AstValidationResult result = validator.validate(context.astGeneration().ast(), model, context.joinPath(), context.session());
        context.astValidation(result);
        publishValidationStage(context.session(), context, result);
        publishValidated(context.session(), context, result);
        if (!result.valid()) {
            throw new IllegalStateException("semantic AST validation failed: " + result.errors());
        }
    }

    private AstValidator resolveValidator(SemanticQueryContext context) {
        List<AstValidator> validators = validatorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(validators);
        for (AstValidator validator : validators) {
            if (validator != null && validator.supports(context.session())) {
                return validator;
            }
        }
        throw new IllegalStateException("No AstValidator available.");
    }

    private void publishValidated(EngineSession session, SemanticQueryContext context, AstValidationResult result) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null) {
            return;
        }
        boolean valid = result != null && result.valid();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", "semantic-query");
        String determinant = ConvEngineAuditStage.SEMANTIC_AST_VALIDATED.name();
        payload.put("determinant", determinant);
        payload.put("valid", valid);
        payload.put("errorCount", result == null || result.errors() == null ? 0 : result.errors().size());
        payload.put("errors", result == null ? List.of() : result.errors());
        payload.put("entity", context == null || context.astGeneration() == null || context.astGeneration().ast() == null
                ? null : context.astGeneration().ast().entity());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("stage", stageCode());
        meta.put("toolCode", resolveToolCode());
        meta.put("astPrepared", context != null && context.astGeneration() != null && context.astGeneration().ast() != null);
        meta.put("astValidated", true);
        meta.put("astValid", valid);
        payload.put("_meta", meta);

        auditService.audit(determinant, conversationId, payload);
        if (verbosePublisher != null) {
            verbosePublisher.publish(session, getClass().getSimpleName(), determinant, null, resolveToolCode(), !valid, payload);
        }
    }

    private void publishValidationStage(EngineSession session, SemanticQueryContext context, AstValidationResult result) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null) {
            return;
        }
        boolean valid = result != null && result.valid();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", "semantic-query");
        String determinant = ConvEngineAuditStage.SEMANTIC_AST_VALIDATION_STAGE.name();
        payload.put("determinant", determinant);
        payload.put("valid", valid);
        payload.put("errorCount", result == null || result.errors() == null ? 0 : result.errors().size());
        payload.put("entity", context == null || context.astGeneration() == null || context.astGeneration().ast() == null
                ? null : context.astGeneration().ast().entity());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("stage", stageCode());
        meta.put("toolCode", resolveToolCode());
        meta.put("astValidationDone", true);
        meta.put("astValid", valid);
        payload.put("_meta", meta);

        auditService.audit(determinant, conversationId, payload);
        if (verbosePublisher != null) {
            verbosePublisher.publish(session, getClass().getSimpleName(), determinant, null, resolveToolCode(), !valid, payload);
        }
    }

    private String resolveToolCode() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        if (cfg == null || cfg.getToolCode() == null || cfg.getToolCode().isBlank()) {
            return "db.semantic.query";
        }
        return cfg.getToolCode().trim();
    }
}
