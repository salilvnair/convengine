package com.github.salilvnair.convengine.cache;

import com.github.salilvnair.convengine.entity.*;
import com.github.salilvnair.convengine.engine.type.RulePhase;
import com.github.salilvnair.convengine.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Service
public class StaticConfigurationCacheService {

    private final RuleRepository ruleRepo;
    private final PendingActionRepository pendingActionRepo;
    private final IntentRepository intentRepo;
    private final IntentClassifierRepository intentClassifierRepo;
    private final OutputSchemaRepository outputSchemaRepo;
    private final PromptTemplateRepository promptTemplateRepo;
    private final ResponseRepository responseRepo;
    private final ContainerConfigRepository containerConfigRepo;
    private final McpToolRepository mcpToolRepo;
    private final McpDbToolRepository mcpDbToolRepo;
    private final McpPlannerRepository mcpPlannerRepo;
    private final PolicyRepository policyRepo;
    private final CeConfigRepository ceConfigRepo;
    @Autowired
    private ObjectProvider<StaticConfigurationCacheService> selfProvider;

    // --- Base Caching Methods ---

    @Cacheable("ce_config")
    public List<CeConfig> getAllConfigs() {
        return ceConfigRepo.findAll();
    }

    @Cacheable("ce_rule")
    public List<CeRule> getAllRules() {
        return ruleRepo.findAll();
    }

    @Cacheable("ce_pending_action")
    public List<CePendingAction> getAllPendingActions() {
        return pendingActionRepo.findAll();
    }

    @Cacheable("ce_intent")
    public List<CeIntent> getAllIntents() {
        return intentRepo.findAll();
    }

    @Cacheable("ce_intent_classifier")
    public List<CeIntentClassifier> getAllIntentClassifiers() {
        return intentClassifierRepo.findAll();
    }

    @Cacheable("ce_output_schema")
    public List<CeOutputSchema> getAllOutputSchemas() {
        return outputSchemaRepo.findAll();
    }

    @Cacheable("ce_prompt_template")
    public List<CePromptTemplate> getAllPromptTemplates() {
        return promptTemplateRepo.findAll();
    }

    @Cacheable("ce_response")
    public List<CeResponse> getAllResponses() {
        return responseRepo.findAll();
    }

    @Cacheable("ce_container_config")
    public List<CeContainerConfig> getAllContainerConfigs() {
        return containerConfigRepo.findAll();
    }

    @Cacheable("ce_mcp_tool")
    public List<CeMcpTool> getAllMcpTools() {
        return mcpToolRepo.findAll();
    }

    @Cacheable("ce_mcp_db_tool")
    public List<CeMcpDbTool> getAllMcpDbTools() {
        return mcpDbToolRepo.findAll();
    }

    @Cacheable("ce_policy")
    public List<CePolicy> getAllPolicies() {
        return policyRepo.findAll();
    }

    @Cacheable("ce_mcp_planner")
    public List<CeMcpPlanner> getAllMcpPlanners() {
        return mcpPlannerRepo.findAll();
    }

    // --- Helper Filter Methods ---

    private StaticConfigurationCacheService self() {
        return selfProvider.getObject();
    }

    public List<CeConfig> findConfigParams(String type, String configKey) {
        return self().getAllConfigs().stream()
                .filter(CeConfig::isEnabled)
                .filter(c -> c.getConfigType() != null && c.getConfigType().equalsIgnoreCase(type))
                .filter(c -> c.getConfigKey() != null && c.getConfigKey().equalsIgnoreCase(configKey))
                .toList();
    }

    // Rules
    public List<CeRule> findEligibleRulesByPhaseAndState(String phase, String state) {
        String normalizedPhase = RulePhase.normalize(phase);
        return self().getAllRules().stream()
                .filter(CeRule::isEnabled)
                .filter(r -> r.getPhase() != null && RulePhase.normalize(r.getPhase()).equalsIgnoreCase(normalizedPhase))
                .filter(r -> isEligibleState(r.getStateCode(), state))
                .sorted(Comparator.comparing(CeRule::getPriority))
                .toList();
    }

    // Pending Actions
    public List<CePendingAction> findEligiblePendingActionsByIntentAndState(String intent, String state) {
        return self().getAllPendingActions().stream()
                .filter(CePendingAction::isEnabled)
                .filter(p -> isEligibleIntent(p.getIntentCode(), intent))
                .filter(p -> isEligibleState(p.getStateCode(), state))
                .sorted(Comparator.comparing(CePendingAction::getPriority)
                        .thenComparing(CePendingAction::getPendingActionId))
                .toList();
    }

    public List<CePendingAction> findEligiblePendingActionsByActionIntentAndState(String actionKey, String intent,
            String state) {
        return self().getAllPendingActions().stream()
                .filter(CePendingAction::isEnabled)
                .filter(p -> p.getActionKey() != null && p.getActionKey().equalsIgnoreCase(actionKey))
                .filter(p -> isEligibleIntent(p.getIntentCode(), intent))
                .filter(p -> isEligibleState(p.getStateCode(), state))
                .sorted(Comparator.comparing(CePendingAction::getPriority)
                        .thenComparing(CePendingAction::getPendingActionId))
                .toList();
    }

    // Container Configs
    public List<CeContainerConfig> findContainerConfigsByIntentAndState(String intentCode, String stateCode) {
        return self().getAllContainerConfigs().stream()
                .filter(CeContainerConfig::isEnabled)
                .filter(c -> c.getIntentCode() != null && c.getIntentCode().equalsIgnoreCase(intentCode))
                .filter(c -> c.getStateCode() != null && c.getStateCode().equalsIgnoreCase(stateCode))
                .sorted(Comparator.comparing(CeContainerConfig::getPriority))
                .toList();
    }

    public List<CeContainerConfig> findContainerConfigsFallbackByState(String stateCode) {
        return self().getAllContainerConfigs().stream()
                .filter(CeContainerConfig::isEnabled)
                .filter(c -> c.getIntentCode() != null && c.getIntentCode().equalsIgnoreCase("ANY"))
                .filter(c -> c.getStateCode() != null && c.getStateCode().equalsIgnoreCase(stateCode))
                .sorted(Comparator.comparing(CeContainerConfig::getPriority))
                .toList();
    }

    public List<CeContainerConfig> findContainerConfigsGlobalFallback() {
        return self().getAllContainerConfigs().stream()
                .filter(CeContainerConfig::isEnabled)
                .filter(c -> c.getIntentCode() != null && c.getIntentCode().equalsIgnoreCase("ANY"))
                .filter(c -> c.getStateCode() != null && c.getStateCode().equalsIgnoreCase("ANY"))
                .sorted(Comparator.comparing(CeContainerConfig::getPriority))
                .toList();
    }

    // Output Schema
    public Optional<CeOutputSchema> findFirstOutputSchema(String intentCode, String stateCode) {
        return self().getAllOutputSchemas().stream()
                .filter(CeOutputSchema::isEnabled)
                .filter(s -> s.getIntentCode() != null && s.getIntentCode().equalsIgnoreCase(intentCode))
                .filter(s -> s.getStateCode() != null && s.getStateCode().equalsIgnoreCase(stateCode))
                .min(Comparator.comparing(CeOutputSchema::getPriority));
    }

    // Prompt Template
    public Optional<CePromptTemplate> findFirstPromptTemplate(String responseType, String intentCode,
            String stateCode) {
        return self().getAllPromptTemplates().stream()
                .filter(CePromptTemplate::isEnabled)
                .filter(p -> p.getResponseType() != null && p.getResponseType().equalsIgnoreCase(responseType))
                .filter(p -> p.getIntentCode() != null && p.getIntentCode().equalsIgnoreCase(intentCode))
                .filter(p -> p.getStateCode() != null && p.getStateCode().equalsIgnoreCase(stateCode))
                .max(Comparator.comparing(CePromptTemplate::getCreatedAt));
    }

    public Optional<CePromptTemplate> findFirstPromptTemplate(String responseType, String intentCode) {
        return self().getAllPromptTemplates().stream()
                .filter(CePromptTemplate::isEnabled)
                .filter(p -> p.getResponseType() != null && p.getResponseType().equalsIgnoreCase(responseType))
                .filter(p -> p.getIntentCode() != null && p.getIntentCode().equalsIgnoreCase(intentCode))
                .filter(p -> p.getStateCode() != null && p.getStateCode().equalsIgnoreCase("ANY"))
                .max(Comparator.comparing(CePromptTemplate::getCreatedAt));
    }

    // Intents
    public List<CeIntent> findEnabledIntents() {
        return self().getAllIntents().stream()
                .filter(CeIntent::isEnabled)
                .sorted(Comparator.comparing(CeIntent::getPriority))
                .toList();
    }

    public Optional<CeIntent> findIntent(String intentCode) {
        return self().getAllIntents().stream()
                .filter(CeIntent::isEnabled)
                .filter(i -> i.getIntentCode() != null && i.getIntentCode().equalsIgnoreCase(intentCode))
                .findFirst();
    }

    // Intent Classifiers
    public List<CeIntentClassifier> findEnabledIntentClassifiers() {
        return self().getAllIntentClassifiers().stream()
                .filter(CeIntentClassifier::isEnabled)
                .sorted(Comparator.comparing(CeIntentClassifier::getPriority))
                .toList();
    }

    // Policies
    public List<CePolicy> findEnabledPolicies() {
        return self().getAllPolicies().stream()
                .filter(CePolicy::isEnabled)
                .sorted(Comparator.comparing(CePolicy::getPriority))
                .toList();
    }

    // MCP Tools
    public List<CeMcpTool> findEnabledMcpTools(String intentCode, String stateCode) {
        return self().getAllMcpTools().stream()
                .filter(CeMcpTool::isEnabled)
                .filter(t -> isEligibleMcpScopeCode(t.getIntentCode(), intentCode))
                .filter(t -> isEligibleMcpScopeCode(t.getStateCode(), stateCode))
                .toList(); // Not explicitly ordered in previous JPQL
    }

    public Optional<CeMcpTool> findMcpTool(String toolCode, String intentCode, String stateCode) {
        return self().getAllMcpTools().stream()
                .filter(CeMcpTool::isEnabled)
                .filter(t -> t.getToolCode() != null && t.getToolCode().equalsIgnoreCase(toolCode))
                .filter(t -> isEligibleMcpScopeCode(t.getIntentCode(), intentCode))
                .filter(t -> isEligibleMcpScopeCode(t.getStateCode(), stateCode))
                .findFirst();
    }

    public Optional<CeMcpDbTool> findMcpDbTool(String toolCode) {
        return self().getAllMcpDbTools().stream()
                .filter(d -> d.getTool() != null && d.getTool().isEnabled())
                .filter(d -> d.getTool().getToolCode() != null && d.getTool().getToolCode().equalsIgnoreCase(toolCode))
                .findFirst();
    }

    public Optional<CeMcpPlanner> findFirstMcpPlanner(String intentCode, String stateCode) {
        return self().getAllMcpPlanners().stream()
                .filter(CeMcpPlanner::isEnabled)
                .filter(p -> isEligibleIntent(p.getIntentCode(), intentCode))
                .filter(p -> isEligibleState(p.getStateCode(), stateCode))
                .sorted(
                        Comparator
                                .comparingInt((CeMcpPlanner p) -> plannerSpecificityScore(p, intentCode, stateCode))
                                .reversed()
                                .thenComparing(CeMcpPlanner::getPlannerId, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst();
    }

    // Responses
    public Optional<CeResponse> findFirstResponse(String stateCode, String intentCode) {
        return self().getAllResponses().stream()
                .filter(CeResponse::isEnabled)
                .filter(r -> r.getStateCode() != null && r.getStateCode().equalsIgnoreCase(stateCode))
                .filter(r -> r.getIntentCode() != null && r.getIntentCode().equalsIgnoreCase(intentCode))
                .min(Comparator.comparing(CeResponse::getPriority));
    }

    public Optional<CeResponse> findFirstResponseFallbackIntent(String stateCode) {
        return self().getAllResponses().stream()
                .filter(CeResponse::isEnabled)
                .filter(r -> r.getStateCode() != null && r.getStateCode().equalsIgnoreCase(stateCode))
                .filter(r -> r.getIntentCode() != null && r.getIntentCode().equalsIgnoreCase("ANY"))
                .min(Comparator.comparing(CeResponse::getPriority));
    }

    public Optional<CeResponse> findFirstResponseAnyIntent(String stateCode) {
        return self().getAllResponses().stream()
                .filter(CeResponse::isEnabled)
                .filter(r -> r.getStateCode() != null && r.getStateCode().equalsIgnoreCase(stateCode))
                .min(Comparator.comparing(CeResponse::getPriority));
    }

    // --- Private Evaluators ---

    private boolean isEligibleState(String dbValue, String userValue) {
        if (dbValue == null) {
            return false;
        }
        dbValue = dbValue.trim();
        if (dbValue.isEmpty()) {
            return false;
        }
        if (dbValue.equalsIgnoreCase("ANY")) {
            return true;
        }
        if (userValue == null || userValue.trim().isEmpty()) {
            return false;
        }
        return dbValue.equalsIgnoreCase(userValue.trim());
    }

    private boolean isEligibleIntent(String dbValue, String userValue) {
        if (dbValue == null) {
            return false;
        }
        dbValue = dbValue.trim();
        if (dbValue.isEmpty()) {
            return false;
        }
        if (dbValue.equalsIgnoreCase("ANY")) {
            return true;
        }
        if (userValue == null || userValue.trim().isEmpty()) {
            return false;
        }
        return dbValue.equalsIgnoreCase(userValue.trim());
    }

    private boolean isEligibleMcpScopeCode(String dbValue, String userValue) {
        if (dbValue == null) {
            return false;
        }
        String normalizedDbValue = dbValue.trim();
        if (normalizedDbValue.isEmpty()) {
            return false;
        }
        if (normalizedDbValue.equalsIgnoreCase("ANY")) {
            return true;
        }
        if (userValue == null || userValue.trim().isEmpty()) {
            return false;
        }
        return normalizedDbValue.equalsIgnoreCase(userValue.trim());
    }

    private int plannerSpecificityScore(CeMcpPlanner planner, String intentCode, String stateCode) {
        int score = 0;
        if (planner.getIntentCode() != null
                && !planner.getIntentCode().isBlank()
                && planner.getIntentCode().equalsIgnoreCase(intentCode)) {
            score += 2;
        }
        if (planner.getStateCode() != null
                && !planner.getStateCode().isBlank()
                && planner.getStateCode().equalsIgnoreCase(stateCode)) {
            score += 1;
        }
        return score;
    }
}
