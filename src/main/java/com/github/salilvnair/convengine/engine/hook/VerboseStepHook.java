package com.github.salilvnair.convengine.engine.hook;

import com.github.salilvnair.convengine.engine.model.StepInfo;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class VerboseStepHook implements EngineStepHook {

    private final VerboseMessagePublisher verboseMessagePublisher;

    @Override
    public void beforeStep(String stepName, EngineSession session) {
        verboseMessagePublisher.publish(session, stepName, "STEP_ENTER", readRuleId(session), readToolCode(session),
                false, metadata(stepName, session, null));
    }

    @Override
    public void afterStep(String stepName, EngineSession session, StepResult result) {
        Map<String, Object> metadata = metadata(stepName, session, result);
        verboseMessagePublisher.publish(session, stepName, "STEP_EXIT", readRuleId(session), readToolCode(session),
                false, metadata);
    }

    @Override
    public void onStepError(String stepName, EngineSession session, Throwable error) {
        Map<String, Object> metadata = metadata(stepName, session, null);
        if (error != null) {
            metadata.put("errorType", error.getClass().getSimpleName());
            metadata.put("errorMessage", String.valueOf(error.getMessage()));
        }
        verboseMessagePublisher.publish(session, stepName, "STEP_ERROR", readRuleId(session), readToolCode(session),
                true, metadata);
    }

    private Map<String, Object> metadata(String stepName, EngineSession session, StepResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stepName", stepName);
        metadata.put("intent", session.getIntent());
        metadata.put("state", session.getState());
        metadata.put("result", result == null ? null : result.getClass().getSimpleName());
        StepInfo stepInfo = session.getStepInfos().get(stepName);
        if (stepInfo != null) {
            metadata.put("stepInfo", stepInfo);
        }
        return metadata;
    }

    private Long readRuleId(EngineSession session) {
        Object raw = session.getInputParams().get("rule_id");
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String value && !value.isBlank()) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String readToolCode(EngineSession session) {
        Object raw = session.getInputParams().get("mcp_tool_code");
        if (raw == null) {
            raw = session.getInputParams().get("tool_code");
        }
        return raw == null ? null : String.valueOf(raw);
    }
}
