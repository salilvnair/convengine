package com.github.salilvnair.convengine.cache;

import com.github.salilvnair.convengine.engine.constants.ConvEngineValue;
import com.github.salilvnair.convengine.engine.constants.MatchTypeConstants;
import com.github.salilvnair.convengine.engine.type.RuleAction;
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

    private final StaticConfigurationCacheService staticCacheService;

    public void validateOrThrow() {
        Set<String> allowedIntents = new LinkedHashSet<>();
        staticCacheService.getAllIntents().stream()
                .filter(CeIntent::isEnabled)
                .map(CeIntent::getIntentCode)
                .map(this::normalize)
                .filter(v -> !v.isEmpty())
                .forEach(allowedIntents::add);
        allowedIntents.add(ConvEngineValue.ANY);
        allowedIntents.add(ConvEngineValue.UNKNOWN);

        Set<String> allowedStates = new LinkedHashSet<>();
        staticCacheService.getAllRules().stream()
                .filter(CeRule::isEnabled)
                .map(CeRule::getStateCode)
                .map(this::normalize)
                .filter(v -> !v.isEmpty())
                .forEach(allowedStates::add);
        staticCacheService.getAllRules().stream()
                .filter(CeRule::isEnabled)
                .filter(v -> RuleAction.SET_STATE.name().equalsIgnoreCase(v.getAction()))
                .map(CeRule::getActionValue)
                .map(this::normalize)
                .filter(v -> !v.isEmpty())
                .forEach(allowedStates::add);
        allowedStates.add(ConvEngineValue.ANY);
        allowedStates.add(ConvEngineValue.UNKNOWN);

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
        validateScope("ce_verbose", staticCacheService.getAllVerboses().stream().filter(CeVerbose::isEnabled).toList(), CeVerbose::getVerboseId,
                CeVerbose::getIntentCode, CeVerbose::getStateCode, allowedIntents, allowedStates, violations);
        validateVerboseRows(staticCacheService.getAllVerboses().stream().filter(CeVerbose::isEnabled).toList(),
                violations);
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

    private void validateVerboseRows(List<CeVerbose> rows, List<String> violations) {
        Set<String> allowedMatches = Set.of(MatchTypeConstants.EXACT, MatchTypeConstants.REGEX,
                MatchTypeConstants.JSON_PATH);
        for (CeVerbose row : rows) {
            String id = String.valueOf(row.getVerboseId());
            String stepMatch = normalize(row.getStepMatch());
            String stepValue = normalize(row.getStepValue());
            String determinant = normalize(row.getDeterminant());
            if (stepMatch.isEmpty()) {
                violations.add("ce_verbose[" + id + "] step_match is null/blank");
            } else if (!allowedMatches.contains(stepMatch)) {
                violations.add("ce_verbose[" + id + "] invalid step_match=" + stepMatch);
            }
            if (stepValue.isEmpty()) {
                violations.add("ce_verbose[" + id + "] step_value is null/blank");
            }
            if (determinant.isEmpty()) {
                violations.add("ce_verbose[" + id + "] determinant is null/blank");
            }
        }
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
