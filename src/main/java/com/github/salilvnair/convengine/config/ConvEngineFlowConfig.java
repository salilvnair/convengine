package com.github.salilvnair.convengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "convengine.flow")
@Getter
@Setter
public class ConvEngineFlowConfig {

    private DialogueAct dialogueAct = new DialogueAct();
    private InteractionPolicy interactionPolicy = new InteractionPolicy();
    private ActionLifecycle actionLifecycle = new ActionLifecycle();
    private ToolOrchestration toolOrchestration = new ToolOrchestration();
    private Guardrail guardrail = new Guardrail();
    private StateGraph stateGraph = new StateGraph();
    private Disambiguation disambiguation = new Disambiguation();
    private Memory memory = new Memory();
    private QueryRewrite queryRewrite = new QueryRewrite();
    private ConversationHistory conversationHistory = new ConversationHistory();

    @Getter
    @Setter
    public static class QueryRewrite {
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class ConversationHistory {
        private int maxTurns = 20;
    }

    @Getter
    @Setter
    public static class DialogueAct {
        private String resolute = "REGEX_THEN_LLM";
        private double llmThreshold = 0.90d;
    }

    @Getter
    @Setter
    public static class InteractionPolicy {
        private boolean executePendingOnAffirm = true;
        private boolean rejectPendingOnNegate = true;
        private boolean fillPendingSlotOnNonNewRequest = true;
        private boolean requireResolvedIntentAndState = true;
        private Map<String, String> matrix = defaultMatrix();

        private static Map<String, String> defaultMatrix() {
            Map<String, String> defaults = new LinkedHashMap<>();
            defaults.put("PENDING_ACTION:AFFIRM", "EXECUTE_PENDING_ACTION");
            defaults.put("PENDING_ACTION:NEGATE", "REJECT_PENDING_ACTION");
            defaults.put("PENDING_SLOT:EDIT", "FILL_PENDING_SLOT");
            defaults.put("PENDING_SLOT:QUESTION", "FILL_PENDING_SLOT");
            defaults.put("PENDING_SLOT:NEGATE", "FILL_PENDING_SLOT");
            defaults.put("PENDING_SLOT:AFFIRM", "FILL_PENDING_SLOT");
            return defaults;
        }
    }

    @Getter
    @Setter
    public static class ActionLifecycle {
        private boolean enabled = true;
        private int ttlTurns = 3;
        private long ttlMinutes = 30L;
    }

    @Getter
    @Setter
    public static class ToolOrchestration {
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Guardrail {
        private boolean enabled = true;
        private boolean sanitizeInput = true;
        private boolean requireApprovalForSensitiveActions = false;
        private boolean approvalGateFailClosed = false;
        private List<String> sensitivePatterns = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class StateGraph {
        private boolean enabled = true;
        private boolean softBlockOnViolation = false;
        private Map<String, List<String>> allowedTransitions = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class Disambiguation {
        private boolean enabled = true;
        private int maxOptions = 5;
    }

    @Getter
    @Setter
    public static class Memory {
        private boolean enabled = true;
        private int summaryMaxChars = 1200;
        private int recentTurnsForSummary = 3;
    }
}
