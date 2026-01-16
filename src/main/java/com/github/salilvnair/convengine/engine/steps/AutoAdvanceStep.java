package com.github.salilvnair.convengine.engine.steps;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * State transitions after extraction/validation, before response resolution.
 *
 * Rules:
 * - If schema resolved AND no schema fields present -> NEED_MORE_INFO
 * - If schema complete -> READY
 * - Otherwise keep existing state.
 */
@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter({
        SchemaExtractionStep.class,
        ValidationStep.class
})
@MustRunBefore(ResponseResolutionStep.class)
public class AutoAdvanceStep implements EngineStep {

    private static final String STATE_IDLE = "IDLE";
    private static final String STATE_READY = "READY";
    private static final String STATE_NEED_MORE_INFO = "NEED_MORE_INFO";

    private final AuditService audit;

    @Override
    public StepResult execute(EngineSession session) {

        if (session.getResolvedSchema() == null) {
            audit.audit("AUTO_ADVANCE_SKIPPED_NO_SCHEMA", session.getConversationId(),
                    "{\"reason\":\"no schema resolved\"}");
            return new StepResult.Continue();
        }

        String schemaJson = session.getResolvedSchema().getJsonSchema();
        String contextJson = session.getContextJson();

        boolean hasAnySchemaValue = JsonUtil.hasAnySchemaValue(contextJson, schemaJson);
        boolean schemaComplete = JsonUtil.isSchemaComplete(schemaJson, contextJson);

        // If schema is complete -> READY (always)
        if (schemaComplete) {
            String prev = safe(session.getConversation().getStateCode(), STATE_IDLE);
            session.getConversation().setStateCode(STATE_READY);
            session.syncFromConversation();

            audit.audit("STATE_TRANSITION", session.getConversationId(),
                    "{\"from\":\"" + JsonUtil.escape(prev) + "\",\"to\":\"" + STATE_READY + "\"}");

            return new StepResult.Continue();
        }

        // If no schema field present yet -> NEED_MORE_INFO (only when idle-ish)
        String currentState = safe(session.getConversation().getStateCode(), STATE_IDLE);

        if (!hasAnySchemaValue && isIdleLike(currentState)) {
            session.getConversation().setStateCode(STATE_NEED_MORE_INFO);
            session.syncFromConversation();

            audit.audit("STATE_TRANSITION", session.getConversationId(),
                    "{\"from\":\"" + JsonUtil.escape(currentState) + "\",\"to\":\"" + STATE_NEED_MORE_INFO + "\",\"reason\":\"schema resolved but no schema fields present\"}");

            return new StepResult.Continue();
        }

        // If partial schema present but not complete, keep NEED_MORE_INFO if already there; otherwise leave state as-is.
        if (hasAnySchemaValue && STATE_IDLE.equalsIgnoreCase(currentState)) {
            session.getConversation().setStateCode(STATE_NEED_MORE_INFO);
            session.syncFromConversation();

            audit.audit("STATE_TRANSITION", session.getConversationId(),
                    "{\"from\":\"" + JsonUtil.escape(currentState) + "\",\"to\":\"" + STATE_NEED_MORE_INFO + "\",\"reason\":\"partial schema present\"}");
        } else {
            audit.audit("AUTO_ADVANCE_NOOP", session.getConversationId(),
                    "{\"state\":\"" + JsonUtil.escape(currentState) + "\",\"hasAnySchemaValue\":" + hasAnySchemaValue + ",\"schemaComplete\":" + schemaComplete + "}");
        }

        return new StepResult.Continue();
    }

    private boolean isIdleLike(String state) {
        // You can expand this list if you have other neutral states.
        return STATE_IDLE.equalsIgnoreCase(state);
    }

    private String safe(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
