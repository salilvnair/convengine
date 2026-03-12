package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstGenerationResult;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.core.AstFilterGroup;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.ast.version.AstCanonicalizer;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.llm.SemanticAstGenerator;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core.SemanticQueryStage;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
@MustRunAfter({SemanticJoinPathStage.class, SemanticClarificationStage.class})
@RequiredArgsConstructor
public class SemanticAstGenerationStage implements SemanticQueryStage {

    private final ObjectProvider<List<SemanticAstGenerator>> generatorsProvider;
    private final AstCanonicalizer astCanonicalizer;
    private final SemanticModelRegistry modelRegistry;
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
        publishResolutionStage(context.session(), context, generated);
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

    private void publishResolutionStage(EngineSession session, SemanticQueryContext context, AstGenerationResult generated) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null) {
            return;
        }
        SemanticModel model = modelRegistry.getModel();
        Set<String> availableMetrics = model == null || model.metrics() == null ? Set.of() : model.metrics().keySet();
        Set<String> astMetrics = extractAstMetrics(generated, availableMetrics);
        Set<String> questionMetrics = inferQuestionMetrics(context == null ? null : context.question(), model);
        boolean metricRequested = !questionMetrics.isEmpty();
        boolean metricUsed = !astMetrics.isEmpty();

        Map<String, Object> payload = new LinkedHashMap<>();
        String determinant = ConvEngineAuditStage.AST_INTERPRETATION_SUMMARY.name();
        payload.put("component", "semantic-query");
        payload.put("determinant", determinant);
        payload.put("question", context == null ? null : context.question());
        payload.put("selectedEntity",
                context == null || context.retrieval() == null || context.retrieval().candidateEntities() == null || context.retrieval().candidateEntities().isEmpty()
                        ? null
                        : context.retrieval().candidateEntities().getFirst().name());
        payload.put("astEntity", generated == null || generated.ast() == null ? null : generated.ast().entity());
        payload.put("availableMetricCount", availableMetrics.size());
        payload.put("availableMetrics", availableMetrics);
        payload.put("metricsMentionedInQuestion", questionMetrics);
        payload.put("metricsUsedInAst", astMetrics);
        payload.put("metricRequested", metricRequested);
        payload.put("metricUsed", metricUsed);
        payload.put("resolutionDecision", metricRequested && !metricUsed ? "METRIC_MENTIONED_BUT_NOT_USED" : "RESOLUTION_OK");
        payload.put("repaired", generated != null && generated.repaired());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("stage", "resolution");
        meta.put("toolCode", resolveToolCode());
        meta.put("astPrepared", generated != null && generated.ast() != null);
        meta.put("astRepaired", generated != null && generated.repaired());
        meta.put("metricRequested", metricRequested);
        meta.put("metricUsed", metricUsed);
        payload.put("_meta", meta);

        auditService.audit(determinant, conversationId, payload);
        if (verbosePublisher != null) {
            boolean alert = metricRequested && !metricUsed;
            verbosePublisher.publish(session, getClass().getSimpleName(), determinant, null, resolveToolCode(), alert, payload);
        }
    }

    private Set<String> extractAstMetrics(AstGenerationResult generated, Set<String> availableMetrics) {
        if (generated == null || generated.ast() == null || availableMetrics == null || availableMetrics.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        if (generated.ast().metrics() != null) {
            generated.ast().metrics().stream()
                    .filter(availableMetrics::contains)
                    .forEach(out::add);
        }
        collectMetricRefs(generated.ast().where(), availableMetrics, out);
        collectMetricRefs(generated.ast().having(), availableMetrics, out);
        return out;
    }

    private void collectMetricRefs(AstFilterGroup group, Set<String> availableMetrics, Set<String> out) {
        if (group == null) {
            return;
        }
        if (group.conditions() != null) {
            for (AstFilter filter : group.conditions()) {
                if (filter != null && filter.field() != null && availableMetrics.contains(filter.field())) {
                    out.add(filter.field());
                }
            }
        }
        if (group.groups() != null) {
            for (AstFilterGroup child : group.groups()) {
                collectMetricRefs(child, availableMetrics, out);
            }
        }
    }

    private Set<String> inferQuestionMetrics(String question, SemanticModel model) {
        if (question == null || question.isBlank() || model == null || model.metrics() == null || model.metrics().isEmpty()) {
            return Set.of();
        }
        String lower = question.toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        model.metrics().forEach((name, metric) -> {
            if (name != null && !name.isBlank() && lower.contains(name.toLowerCase(Locale.ROOT))) {
                out.add(name);
                return;
            }
            if (metric != null && metric.description() != null && !metric.description().isBlank()) {
                Set<String> tokens = tokenize(metric.description());
                int hits = 0;
                for (String token : tokens) {
                    if (token.length() > 3 && lower.contains(token)) {
                        hits++;
                    }
                }
                if (hits >= 2) {
                    out.add(name);
                }
            }
        });
        return out;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+");
        Set<String> out = new LinkedHashSet<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }

    private String resolveToolCode() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic();
        if (cfg == null || cfg.getToolCode() == null || cfg.getToolCode().isBlank()) {
            return "db.semantic.query";
        }
        return cfg.getToolCode().trim();
    }
}
