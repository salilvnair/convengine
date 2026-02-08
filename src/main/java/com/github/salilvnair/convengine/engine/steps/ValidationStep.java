package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.ccf.core.model.ContainerComponentRequest;
import com.github.salilvnair.ccf.core.model.ContainerComponentResponse;
import com.github.salilvnair.ccf.core.model.PageInfoRequest;
import com.github.salilvnair.ccf.core.model.type.RequestType;
import com.github.salilvnair.ccf.service.CcfCoreService;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.container.interceptor.ContainerDataInterceptorExecutor;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeContainerConfig;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeValidationSnapshot;
import com.github.salilvnair.convengine.llm.context.LlmInvocationContext;
import com.github.salilvnair.convengine.llm.core.LlmClient;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.prompt.renderer.PromptTemplateRenderer;
import com.github.salilvnair.convengine.repo.ContainerConfigRepository;
import com.github.salilvnair.convengine.repo.PromptTemplateRepository;
import com.github.salilvnair.convengine.repo.ValidationSnapshotRepository;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
public class ValidationStep implements EngineStep {

    private static final String PURPOSE_CONTAINER_DATA_VALIDATION = "CONTAINER_DATA_VALIDATION";

    private final ContainerConfigRepository containerConfigRepo;
    private final CcfCoreService ccfCoreService;

    private final PromptTemplateRepository promptTemplateRepo;
    private final PromptTemplateRenderer renderer;
    private final LlmClient llm;

    private final RulesStep rulesStep;
    private final AuditService audit;
    private final ObjectMapper mapper;

    private final ValidationSnapshotRepository validationSnapshotRepo;
    private final ContainerDataInterceptorExecutor interceptorExecutor;

    @Override
    public StepResult execute(EngineSession session) {

        if (session.getResolvedSchema() == null) {
            audit.audit("VALIDATION_SKIPPED_NO_SCHEMA", session.getConversationId(),
                    mapOf("reason", "no schema resolved"));
            return new StepResult.Continue();
        }

        boolean shouldValidate = JsonUtil.hasAnySchemaValue(
                session.getContextJson(),
                session.getResolvedSchema().getJsonSchema()
        );

        if (!shouldValidate) {
            audit.audit("VALIDATION_SKIPPED_NO_SCHEMA_INPUT", session.getConversationId(),
                    mapOf("reason", "no schema fields present"));
            return new StepResult.Continue();
        }

        String validationTables = buildValidationTablesJson(session);

        if (validationTables == null) {
            audit.audit("VALIDATION_SKIPPED_NO_TABLES", session.getConversationId(),
                    mapOf("reason", "no container configs or no inputs"));
            return new StepResult.Continue();
        }

        // Do NOT merge validation_tables into convo context (purge from UI context).
        // Store only in session field + snapshot table.
        session.setValidationTablesJson(validationTables);

        audit.audit("VALIDATION_TABLES_BUILT", session.getConversationId(),
                mapOf("bytes", validationTables.length()));

        // sanitize context used for decision so previous validation_decision doesn't poison next
        String validationContext = removeTopLevelField(session.getContextJson(), "validation_decision");
        audit.audit("VALIDATION_CONTEXT_SANITIZED", session.getConversationId(),
                mapOf("removed", "validation_decision"));

        CePromptTemplate template = resolvePromptTemplate(PURPOSE_CONTAINER_DATA_VALIDATION, session.getIntent());
        session.putInputParam("session", session.sessionDict());
        session.putInputParam("context", session.contextDict());
        session.putInputParam("extracted_data", session.extractedDataDict());

        PromptTemplateContext promptTemplateContext = PromptTemplateContext
                                                        .builder()
                                                        .context(session.getContextJson())
                                                        .userInput(session.getUserText())
                                                        .schemaJson(session.getResolvedSchema() != null ? session.getResolvedSchema().getJsonSchema() : null)
                                                        .containerDataJson(session.getContainerDataJson())
                                                        .validationJson(session.getValidationTablesJson())
                                                        .extra(session.getInputParams())
                                                        .build();
        String systemPrompt = renderer.render(template.getSystemPrompt(), promptTemplateContext);
        String userPrompt = renderer.render(template.getUserPrompt(), promptTemplateContext);

        Map<String, Object> llmInputPayload = new LinkedHashMap<>();
        llmInputPayload.put("system_prompt", systemPrompt);
        llmInputPayload.put("user_prompt", userPrompt);
        llmInputPayload.put("validation_tables", validationTables);
        audit.audit("VALIDATION_DECISION_LLM_INPUT", session.getConversationId(), llmInputPayload);

        LlmInvocationContext.set(session.getConversationId(), session.getIntent(), session.getState());

        String decisionText = llm.generateText(systemPrompt + "\n\n" + userPrompt, validationContext);
        session.setValidationDecision(decisionText);

        audit.audit("VALIDATION_DECISION_LLM_OUTPUT", session.getConversationId(),
                mapOf("decision", decisionText));

        // Save snapshot for replay (history)
        saveSnapshot(session, validationTables, decisionText);

        // Merge ONLY decision into context (optional). If you also want to purge this from UI,
        // then remove this merge and rely entirely on snapshots + audits.
        try {
            String mergeJson = "{\"validation_decision\":\"" + JsonUtil.escape(decisionText) + "\"}";
            session.setContextJson(JsonUtil.merge(session.getContextJson(), mergeJson));
            session.getConversation().setContextJson(session.getContextJson());
            audit.audit("VALIDATION_DECISION_MERGED", session.getConversationId(),
                    mapOf("validation_decision", decisionText));
        } catch (Exception e) {
            audit.audit("VALIDATION_DECISION_MERGE_FAILED", session.getConversationId(),
                    mapOf("error", e.getMessage()));
        }

        // Apply ce_rule on decision text unless already READY
        if (!"READY".equalsIgnoreCase(session.getConversation().getStateCode())) {
            StepResult ruleResult = rulesStep.execute(session);
            if (ruleResult instanceof StepResult.Stop stop) {
                return stop;
            }
        }

        session.syncFromConversation();
        return new StepResult.Continue();
    }

    private void saveSnapshot(EngineSession session, String validationTablesJson, String decisionText) {
        try {
            CeValidationSnapshot snap = CeValidationSnapshot.builder()
                    .conversationId(session.getConversationId())
                    .intentCode(session.getIntent())
                    .stateCode(session.getState())
                    .schemaId(session.getResolvedSchema() != null ? session.getResolvedSchema().getSchemaId() : null)
                    .validationTables(validationTablesJson)
                    .validationDecision(decisionText)
                    .createdAt(OffsetDateTime.now())
                    .build();
            validationSnapshotRepo.save(snap);

            audit.audit("VALIDATION_SNAPSHOT_SAVED", session.getConversationId(),
                    mapOf("bytes", validationTablesJson.length()));
        } catch (Exception e) {
            audit.audit("VALIDATION_SNAPSHOT_SAVE_FAILED", session.getConversationId(),
                    mapOf("error", e.getMessage()));
        }
    }

    private String buildValidationTablesJson(EngineSession session) {

        List<CeContainerConfig> configs =
                containerConfigRepo.findByIntentAndState(
                        session.getIntent(),
                        session.getState()
                );

        if (configs == null || configs.isEmpty()) return null;

        ObjectNode root = session.getMapper().createObjectNode();

        for (CeContainerConfig cfg : configs) {

            String key = cfg.getInputParamName();
            Object value = extractValueFromContext(session, key);

            if (value == null) {
                audit.audit("VALIDATION_SKIPPED_MISSING_INPUT", session.getConversationId(),
                        mapOf("input_param_name", key));
                continue;
            }

            try {
                ContainerComponentRequest request = new ContainerComponentRequest();

                Map<String, Object> inputParams = new HashMap<>();
                inputParams.put(key, value);
                if (session.getInputParams() != null) {
                    inputParams.putAll(session.getInputParams());
                }
                if (session.getEngineContext().getInputParams() != null) {
                    inputParams.putAll(session.getEngineContext().getInputParams());
                }

                PageInfoRequest pageInfoRequest = PageInfoRequest.builder()
                        .userId("convengine")
                        .loggedInUserId("convengine")
                        .pageId(cfg.getPageId())
                        .sectionId(cfg.getSectionId())
                        .containerId(cfg.getContainerId())
                        .inputParams(inputParams)
                        .build();

                request.setPageInfo(List.of(pageInfoRequest));
                request.setRequestTypes(List.of(RequestType.CONTAINER));

                Map<String, Object> ccfRequestPayload = new LinkedHashMap<>();
                ccfRequestPayload.put("pageId", cfg.getPageId());
                ccfRequestPayload.put("sectionId", cfg.getSectionId());
                ccfRequestPayload.put("containerId", cfg.getContainerId());
                ccfRequestPayload.put("input_param_name", key);
                ccfRequestPayload.put("value", String.valueOf(value));
                audit.audit("VALIDATION_CCF_REQUEST", session.getConversationId(), ccfRequestPayload);

                interceptorExecutor.beforeExecute(request, session);
                ContainerComponentResponse resp = ccfCoreService.execute(request);
                resp = interceptorExecutor.afterExecute(resp, session);
                JsonNode respNode = session.getMapper().valueToTree(resp);

                root.set(key, respNode);

                audit.audit("VALIDATION_CCF_RESPONSE", session.getConversationId(),
                        mapOf("input_param_name", key, "ccf", respNode.toString()));

            } catch (Exception e) {
                audit.audit("VALIDATION_CCF_FAILED", session.getConversationId(),
                        mapOf("input_param_name", key, "error", e.getMessage()));
            }
        }

        if (root.isEmpty()) return null;
        return root.toString();
    }

    /**
     * Supports: string, number, boolean, array of string/number/boolean, object(map)
     */
    private Object extractValueFromContext(EngineSession session, String key) {
        return session.extractValueFromContext(key);
    }

    private CePromptTemplate resolvePromptTemplate(String purpose, String intentCode) {
        return promptTemplateRepo
                .findFirstByEnabledTrueAndPurposeAndIntentCodeOrderByCreatedAtDesc(purpose, intentCode)
                .orElseGet(() -> promptTemplateRepo
                        .findFirstByEnabledTrueAndPurposeAndIntentCodeIsNullOrderByCreatedAtDesc(purpose)
                        .orElseThrow(() -> new IllegalStateException("No enabled ce_prompt_template found for purpose=" + purpose)));
    }

    private String removeTopLevelField(String json, String fieldName) {
        if (json == null || json.isBlank() || fieldName == null || fieldName.isBlank()) return json;
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isObject()) return json;
            ObjectNode obj = (ObjectNode) root;
            obj.remove(fieldName);
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return json;
        }
    }

    private Map<String, Object> mapOf(Object... kvPairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            map.put(String.valueOf(kvPairs[i]), kvPairs[i + 1]);
        }
        return map;
    }
}
