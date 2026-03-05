package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.JoinPathPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.AstPlanningInterceptor;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.AstPlanner;
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
@MustRunAfter(SemanticRetrievalStage.class)
@RequiredArgsConstructor
public class SemanticJoinPathStage implements SemanticQueryStage {

    private final ObjectProvider<List<AstPlanner>> plannersProvider;
    private final ObjectProvider<List<AstPlanningInterceptor>> planningInterceptorsProvider;
    private final ConvEngineMcpConfig mcpConfig;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;

    @Override
    public String stageCode() {
        return "join-path";
    }

    @Override
    public void execute(SemanticQueryContext context) {
        if (context.retrieval() == null) {
            throw new IllegalStateException("retrieval must be completed before join-path stage");
        }
        AstPlanner planner = resolvePlanner(context);
        List<AstPlanningInterceptor> planningInterceptors = planningInterceptorsProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(planningInterceptors);
        for (AstPlanningInterceptor interceptor : planningInterceptors) {
            if (interceptor != null && interceptor.supports(context.session())) {
                interceptor.beforePlan(context.retrieval(), context.session());
            }
        }
        JoinPathPlan plan;
        try {
            plan = planner.plan(context.retrieval(), context.session());
            for (AstPlanningInterceptor interceptor : planningInterceptors) {
                if (interceptor != null && interceptor.supports(context.session())) {
                    plan = interceptor.afterPlan(plan, context.session());
                }
            }
        } catch (Exception ex) {
            for (AstPlanningInterceptor interceptor : planningInterceptors) {
                if (interceptor != null && interceptor.supports(context.session())) {
                    interceptor.onError(context.retrieval(), context.session(), ex);
                }
            }
            throw ex;
        }
        context.joinPath(plan);
        publish(context.session(), ConvEngineAuditStage.JOIN_PATH_RESOLVED.name(), plan, false);
    }

    private AstPlanner resolvePlanner(SemanticQueryContext context) {
        List<AstPlanner> planners = plannersProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(planners);
        for (AstPlanner planner : planners) {
            if (planner != null && planner.supports(context.session())) {
                return planner;
            }
        }
        throw new IllegalStateException("No AstPlanner available.");
    }

    private void publish(EngineSession session, String determinant, JoinPathPlan plan, boolean error) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", "semantic-query");
        payload.put("determinant", determinant);
        payload.put("baseTable", plan == null ? null : plan.baseTable());
        payload.put("requiredTablesCount", plan == null || plan.requiredTables() == null ? 0 : plan.requiredTables().size());
        payload.put("unresolvedTablesCount", plan == null || plan.unresolvedTables() == null ? 0 : plan.unresolvedTables().size());
        payload.put("edgesCount", plan == null || plan.edges() == null ? 0 : plan.edges().size());
        payload.put("confidence", plan == null ? null : plan.confidence());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("stage", stageCode());
        meta.put("toolCode", resolveToolCode());
        meta.put("joinPathResolved", ConvEngineAuditStage.JOIN_PATH_RESOLVED.name().equals(determinant));
        meta.put("error", error);
        payload.put("_meta", meta);

        auditService.audit(determinant, conversationId, payload);
        if (verbosePublisher != null) {
            verbosePublisher.publish(session, getClass().getSimpleName(), determinant, null, resolveToolCode(), error, payload);
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
