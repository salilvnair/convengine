package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.version.AstCanonicalizer;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.llm.SemanticAstGenerator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
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
@MustRunAfter(SemanticJoinPathStage.class)
@RequiredArgsConstructor
public class SemanticAstGenerationStage implements SemanticQueryStage {

    private final ObjectProvider<List<SemanticAstGenerator>> generatorsProvider;
    private final AstCanonicalizer astCanonicalizer;
    private final ConvEngineMcpConfig mcpConfig;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;

    @Override
    public String stageCode() {
        return "ast-generation";
    }

    @Override
    public void execute(SemanticQueryContext context) {
        if (context.retrieval() == null || context.joinPath() == null) {
            throw new IllegalStateException("retrieval and join-path must complete before ast-generation stage");
        }
        SemanticAstGenerator generator = resolveGenerator(context);
        AstGenerationResult generated = generator.generate(context.question(), context.retrieval(), context.joinPath(), context.session());
        context.astGeneration(generated);
        if (generated != null && generated.ast() != null) {
            context.canonicalAst(astCanonicalizer.fromV1(generated.ast()));
        }
        publishAstGenerated(context.session(), generated);
    }

    private SemanticAstGenerator resolveGenerator(SemanticQueryContext context) {
        List<SemanticAstGenerator> generators = generatorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(generators);
        for (SemanticAstGenerator generator : generators) {
            if (generator != null && generator.supports(context.session())) {
                return generator;
            }
        }
        throw new IllegalStateException("No SemanticAstGenerator available.");
    }

    private void publishAstGenerated(EngineSession session, AstGenerationResult generated) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", "semantic-query");
        String determinant = ConvEngineAuditStage.AST_GENERATED.name();
        payload.put("determinant", determinant);
        payload.put("entity", generated == null || generated.ast() == null ? null : generated.ast().entity());
        payload.put("selectCount", generated == null || generated.ast() == null || generated.ast().select() == null ? 0 : generated.ast().select().size());
        payload.put("filterCount", generated == null || generated.ast() == null || generated.ast().filters() == null ? 0 : generated.ast().filters().size());
        payload.put("groupByCount", generated == null || generated.ast() == null || generated.ast().groupBy() == null ? 0 : generated.ast().groupBy().size());
        payload.put("limit", generated == null || generated.ast() == null ? null : generated.ast().limit());
        payload.put("repaired", generated != null && generated.repaired());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("stage", stageCode());
        meta.put("toolCode", resolveToolCode());
        meta.put("astPrepared", generated != null && generated.ast() != null);
        meta.put("astRepaired", generated != null && generated.repaired());
        payload.put("_meta", meta);

        auditService.audit(determinant, conversationId, payload);
        if (verbosePublisher != null) {
            verbosePublisher.publish(session, getClass().getSimpleName(), determinant, null, resolveToolCode(), false, payload);
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
