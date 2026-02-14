package com.github.salilvnair.convengine.audit;

import com.github.salilvnair.convengine.api.dto.AuditStageTraceResponse;
import com.github.salilvnair.convengine.api.dto.AuditStepTraceResponse;
import com.github.salilvnair.convengine.api.dto.AuditTraceResponse;
import com.github.salilvnair.convengine.entity.CeAudit;
import com.github.salilvnair.convengine.repo.AuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AuditTraceService {

    private final AuditRepository auditRepository;
    private final AuditPayloadMapper payloadMapper;

    public AuditTraceResponse trace(UUID conversationId) {
        List<CeAudit> audits = auditRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<AuditStageTraceResponse> stages = new ArrayList<>();
        List<MutableStepTrace> steps = new ArrayList<>();
        Deque<MutableStepTrace> stack = new ArrayDeque<>();

        for (CeAudit audit : audits) {
            Map<String, Object> payload = payloadAsMap(audit.getPayloadJson());
            AuditStageTraceResponse stageTrace = new AuditStageTraceResponse(
                    audit.getAuditId(),
                    audit.getStage(),
                    toIso(audit.getCreatedAt()),
                    payload
            );
            stages.add(stageTrace);

            if ("STEP_ENTER".equalsIgnoreCase(audit.getStage())) {
                MutableStepTrace step = new MutableStepTrace();
                step.step = str(payload.get("step"));
                step.stepClass = str(payload.get("stepClass"));
                step.sourceFile = str(payload.get("sourceFile"));
                step.startedAt = audit.getCreatedAt();
                step.status = "RUNNING";
                steps.add(step);
                stack.push(step);
                continue;
            }

            if ("STEP_EXIT".equalsIgnoreCase(audit.getStage()) || "STEP_ERROR".equalsIgnoreCase(audit.getStage())) {
                String stepName = str(payload.get("step"));
                MutableStepTrace step = findAndPopStep(stack, stepName);
                if (step != null) {
                    step.endedAt = audit.getCreatedAt();
                    step.status = "STEP_ERROR".equalsIgnoreCase(audit.getStage()) ? "ERROR" : "OK";
                    step.error = str(payload.get("errorMessage"));
                    step.durationMs = asLong(payload.get("durationMs"));
                }
                continue;
            }

            if (!stack.isEmpty()) {
                stack.peek().stages.add(stageTrace);
            }
        }

        List<AuditStepTraceResponse> outSteps = new ArrayList<>();
        for (MutableStepTrace step : steps) {
            outSteps.add(new AuditStepTraceResponse(
                    step.step,
                    step.stepClass,
                    step.sourceFile,
                    step.status,
                    toIso(step.startedAt),
                    toIso(step.endedAt),
                    step.durationMs,
                    step.error,
                    step.stages
            ));
        }

        return new AuditTraceResponse(conversationId, outSteps, stages);
    }

    private MutableStepTrace findAndPopStep(Deque<MutableStepTrace> stack, String stepName) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stepName == null || stepName.isBlank()) {
            return stack.pop();
        }
        Iterator<MutableStepTrace> iterator = stack.iterator();
        MutableStepTrace target = null;
        while (iterator.hasNext()) {
            MutableStepTrace candidate = iterator.next();
            if (stepName.equalsIgnoreCase(candidate.step)) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            return stack.pop();
        }
        stack.remove(target);
        return target;
    }

    private String toIso(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }

    private Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> payloadAsMap(String payloadJson) {
        return payloadMapper.payloadAsMap(payloadJson);
    }

    private static final class MutableStepTrace {
        private String step;
        private String stepClass;
        private String sourceFile;
        private String status;
        private OffsetDateTime startedAt;
        private OffsetDateTime endedAt;
        private Long durationMs;
        private String error;
        private final List<AuditStageTraceResponse> stages = new ArrayList<>();
    }
}
