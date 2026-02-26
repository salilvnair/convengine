package com.github.salilvnair.convengine.cache;

import com.github.salilvnair.convengine.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class StaticScopeIntegrityValidator {

    private static final String ANY = "ANY";
    private static final String UNKNOWN = "UNKNOWN";

    private final StaticConfigurationCacheService staticCacheService;

    public void validateOrThrow() {
        Set<String> allowedIntents = new LinkedHashSet<>();
        staticCacheService.getAllIntents().stream()
                .filter(CeIntent::isEnabled)
                .map(CeIntent::getIntentCode)
                .map(this::normalize)
                .filter(v -> !v.isEmpty())
                .forEach(allowedIntents::add);
        allowedIntents.add(ANY);
        allowedIntents.add(UNKNOWN);

        Set<String> allowedStates = new LinkedHashSet<>();
        staticCacheService.getAllRules().stream()
                .filter(CeRule::isEnabled)
                .map(CeRule::getStateCode)
                .map(this::normalize)
                .filter(v -> !v.isEmpty())
                .forEach(allowedStates::add);
        staticCacheService.getAllRules().stream()
                .filter(CeRule::isEnabled)
                .filter(v -> "SET_STATE".equalsIgnoreCase(v.getAction()))
                .map(CeRule::getActionValue)
                .map(this::normalize)
                .filter(v -> !v.isEmpty())
                .forEach(allowedStates::add);
        allowedStates.add(ANY);
        allowedStates.add(UNKNOWN);

        List<String> violations = new ArrayList<>();
        validateScope("ce_rule", staticCacheService.getAllRules().stream().filter(CeRule::isEnabled).toList(), CeRule::getRuleId, CeRule::getIntentCode,
                CeRule::getStateCode, allowedIntents, allowedStates, violations);
        validateScope("ce_pending_action", staticCacheService.getAllPendingActions().stream().filter(CePendingAction::isEnabled).toList(), CePendingAction::getPendingActionId,
                CePendingAction::getIntentCode, CePendingAction::getStateCode, allowedIntents, allowedStates, violations);
        validateScope("ce_prompt_template", staticCacheService.getAllPromptTemplates().stream().filter(CePromptTemplate::isEnabled).toList(), CePromptTemplate::getTemplateId,
                CePromptTemplate::getIntentCode, CePromptTemplate::getStateCode, allowedIntents, allowedStates, violations);
        validateScope("ce_response", staticCacheService.getAllResponses().stream().filter(CeResponse::isEnabled).toList(), CeResponse::getResponseId,
                CeResponse::getIntentCode, CeResponse::getStateCode, allowedIntents, allowedStates, violations);
        validateScope("ce_container_config", staticCacheService.getAllContainerConfigs().stream().filter(CeContainerConfig::isEnabled).toList(), CeContainerConfig::getId,
                CeContainerConfig::getIntentCode, CeContainerConfig::getStateCode, allowedIntents, allowedStates,
                violations);
        validateScope("ce_output_schema", staticCacheService.getAllOutputSchemas().stream().filter(CeOutputSchema::isEnabled).toList(), CeOutputSchema::getSchemaId,
                CeOutputSchema::getIntentCode, CeOutputSchema::getStateCode, allowedIntents, allowedStates, violations);
        validateScope("ce_mcp_tool", staticCacheService.getAllMcpTools().stream().filter(CeMcpTool::isEnabled).toList(), CeMcpTool::getToolId, CeMcpTool::getIntentCode,
                CeMcpTool::getStateCode, allowedIntents, allowedStates, violations);
        validateScope("ce_mcp_planner", staticCacheService.getAllMcpPlanners().stream().filter(CeMcpPlanner::isEnabled).toList(), CeMcpPlanner::getPlannerId,
                CeMcpPlanner::getIntentCode, CeMcpPlanner::getStateCode, allowedIntents, allowedStates, violations);
        validateScope("ce_intent_classifier", staticCacheService.getAllIntentClassifiers().stream().filter(CeIntentClassifier::isEnabled).toList(),
                CeIntentClassifier::getClassifierId, CeIntentClassifier::getIntentCode, CeIntentClassifier::getStateCode,
                allowedIntents, allowedStates, violations);

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "ConvEngine static scope validation failed. Fix intent_code/state_code values. Violations: "
                            + String.join(" | ", violations));
        }
    }

    private <T> void validateScope(
            String table,
            List<T> rows,
            SupplierId<T> idExtractor,
            ValueExtractor<T> intentExtractor,
            ValueExtractor<T> stateExtractor,
            Set<String> allowedIntents,
            Set<String> allowedStates,
            List<String> violations) {
        for (T row : rows) {
            String rowId = String.valueOf(idExtractor.id(row));
            String intent = normalize(intentExtractor.value(row));
            String state = normalize(stateExtractor.value(row));

            if (intent.isEmpty()) {
                violations.add(table + "[" + rowId + "] intent_code is null/blank");
                continue;
            }
            if (state.isEmpty()) {
                violations.add(table + "[" + rowId + "] state_code is null/blank");
                continue;
            }
            if (!allowedIntents.contains(intent)) {
                violations.add(table + "[" + rowId + "] invalid intent_code=" + intent);
            }
            if (!allowedStates.contains(state)) {
                violations.add(table + "[" + rowId + "] invalid state_code=" + state);
            }
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface SupplierId<T> {
        Object id(T row);
    }

    @FunctionalInterface
    private interface ValueExtractor<T> {
        String value(T row);
    }
}
