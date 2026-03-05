package com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.provider;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import com.github.salilvnair.convengine.engine.core.step.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.core.step.annotation.TerminalStep;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.context.SemanticQueryContext;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.runtime.stage.core.SemanticQueryStage;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.summary.SemanticResultSummarizer;
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
@TerminalStep
@MustRunAfter(SemanticSqlExecuteStage.class)
@RequiredArgsConstructor
public class SemanticResultSummaryStage implements SemanticQueryStage {

    private final ObjectProvider<List<SemanticResultSummarizer>> summarizersProvider;
    private final ConvEngineMcpConfig mcpConfig;
    private final AuditService auditService;
    private final VerboseMessagePublisher verbosePublisher;

    @Override
    public String stageCode() {
        return "summary";
    }

    @Override
    public void execute(SemanticQueryContext context) {
        if (context.executionResult() == null) {
            throw new IllegalStateException("sql-execute must complete before summary stage");
        }
        SemanticResultSummarizer summarizer = resolveSummarizer(context);
        String summary = summarizer.summarize(context.executionResult(), context);
        context.summary(summary);
        publishSummary(context.session(), summary);
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

    private void publishSummary(EngineSession session, String summary) {
        UUID conversationId = session == null ? null : session.getConversationId();
        if (conversationId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", "semantic-query");
        String determinant = ConvEngineAuditStage.RESULT_SUMMARIZED.name();
        payload.put("determinant", determinant);
        payload.put("summaryPreview", abbreviate(summary, 300));
        payload.put("summaryLength", summary == null ? 0 : summary.length());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("component", "semantic-query");
        meta.put("stage", stageCode());
        meta.put("toolCode", resolveToolCode());
        meta.put("summaryPrepared", summary != null && !summary.isBlank());
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
