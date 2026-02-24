package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.config.ConvEngineFlowConfig;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.helper.SessionContextHelper;
import com.github.salilvnair.convengine.engine.history.model.ConversationTurn;
import com.github.salilvnair.convengine.engine.memory.ConversationMemoryStore;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.model.TextPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(ResponseResolutionStep.class)
@MustRunBefore(PersistConversationStep.class)
public class MemoryStep implements EngineStep {

    private final ConvEngineFlowConfig flowConfig;
    private final SessionContextHelper contextHelper;
    private final AuditService audit;
    private final List<ConversationMemoryStore> memoryStores;

    @Override
    public StepResult execute(EngineSession session) {
        if (!flowConfig.getMemory().isEnabled()) {
            return new StepResult.Continue();
        }

        String recalled = null;
        for (ConversationMemoryStore store : memoryStores) {
            try {
                String value = store.read(session);
                if (value != null && !value.isBlank()) {
                    recalled = value;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (recalled != null) {
            session.putInputParam(ConvEngineInputParamKey.MEMORY_RECALL, recalled);
        }

        String summary = buildSummary(session);
        if (summary.length() > flowConfig.getMemory().getSummaryMaxChars()) {
            summary = summary.substring(0, flowConfig.getMemory().getSummaryMaxChars());
        }
        session.putInputParam(ConvEngineInputParamKey.MEMORY_SESSION_SUMMARY, summary);

        ObjectNode root = contextHelper.readRoot(session);
        ObjectNode memoryNode = contextHelper.ensureObject(root, "memory");
        memoryNode.put("session_summary", summary);
        if (recalled != null) {
            memoryNode.put("recalled_summary", recalled);
        }
        contextHelper.writeRoot(session, root);

        for (ConversationMemoryStore store : memoryStores) {
            try {
                store.write(session, summary);
            } catch (Exception ignored) {
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summaryChars", summary.length());
        payload.put("recalled", recalled != null);
        payload.put("stores", memoryStores.size());
        payload.put("intent", session.getIntent());
        payload.put("state", session.getState());
        audit.audit(ConvEngineAuditStage.MEMORY_UPDATED, session.getConversationId(), payload);
        return new StepResult.Continue();
    }

    private String buildSummary(EngineSession session) {
        StringBuilder out = new StringBuilder();
        out.append("intent=").append(value(session.getIntent()))
                .append(", state=").append(value(session.getState()));
        out.append(", user=").append(value(session.getUserText()));

        List<ConversationTurn> history = session.conversionHistory();
        int maxTurns = Math.max(0, flowConfig.getMemory().getRecentTurnsForSummary());
        if (!history.isEmpty() && maxTurns > 0) {
            int from = Math.max(0, history.size() - maxTurns);
            out.append(", recentTurns=[");
            for (int i = from; i < history.size(); i++) {
                ConversationTurn turn = history.get(i);
                if (turn == null) {
                    continue;
                }
                out.append("{u=").append(value(turn.user()))
                        .append(",a=").append(value(turn.assistant()))
                        .append("}");
            }
            out.append("]");
        }

        if (session.getPayload() instanceof TextPayload textPayload && textPayload.text() != null) {
            out.append(", response=").append(textPayload.text());
        }
        return out.toString();
    }

    private String value(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
    }
}
