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
import com.github.salilvnair.convengine.container.service.ContainerDataTransformerService;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeContainerConfig;
import com.github.salilvnair.convengine.repo.ContainerConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(FallbackIntentStateStep.class)
public class AddContainerDataStep implements EngineStep {

    private final ContainerConfigRepository containerConfigRepo;
    private final CcfCoreService ccfCoreService;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final ContainerDataTransformerService transformerService;
    private final ContainerDataInterceptorExecutor interceptorExecutor;

    @SneakyThrows
    @Override
    public StepResult execute(EngineSession session) {

        List<CeContainerConfig> configs =
                containerConfigRepo.findByIntentAndState(
                        session.getIntent(),
                        session.getState()
                );

        if (configs.isEmpty()) {
            configs = containerConfigRepo.findFallbackByState(session.getState());
        }

        if (configs.isEmpty()) {
            configs = containerConfigRepo.findGlobalFallback();
        }

        if (configs.isEmpty()) {
            Map<String, String> reasonMap = Map.of(
                    "reason", "no container configs for intent/state",
                    "intent", session.getIntent(),
                    "state", session.getState()
            );
            audit.audit(
                    "CONTAINER_DATA_SKIPPED",
                    session.getConversationId(),
                    mapper.writeValueAsString(reasonMap)
            );
            return new StepResult.Continue();
        }

        ObjectNode containerRoot = mapper.createObjectNode();

        for (CeContainerConfig cfg : configs) {

            try {
                Map<String, Object> inputParams = new HashMap<>();
                String key = cfg.getInputParamName();
                Object value = session.extractValueFromContext(key);
                if(value == null) {
                    value = session.getUserText();
                }
                inputParams.put(key, value);
                if (session.getEngineContext().getInputParams() != null) {
                    inputParams.putAll(session.getEngineContext().getInputParams());
                }

                PageInfoRequest pageInfo = PageInfoRequest.builder()
                                                .userId("convengine")
                                                .loggedInUserId("convengine")
                                                .pageId(cfg.getPageId())
                                                .sectionId(cfg.getSectionId())
                                                .containerId(cfg.getContainerId())
                                                .inputParams(inputParams)
                                                .build();

                ContainerComponentRequest req = new ContainerComponentRequest();
                req.setPageInfo(List.of(pageInfo));
                req.setRequestTypes(List.of(RequestType.CONTAINER));
                interceptorExecutor.beforeExecute(req, session);
                ContainerComponentResponse resp = ccfCoreService.execute(req);
                resp = interceptorExecutor.afterExecute(resp, session);
                // find classes with @ContainerDataTransformer(state, intent) to transform resp if needed
                Map<String, Object> transformedData = transformerService.transformIfApplicable(resp, session, inputParams);
                JsonNode responseNode = transformedData == null ? mapper.valueToTree(resp) : mapper.valueToTree(transformedData);
                containerRoot.set(cfg.getInputParamName(), responseNode);
                Map<String, Object> jsonMap = Map.of(
                        "containerId", cfg.getContainerId(),
                        "pageId", cfg.getPageId(),
                        "sectionId", cfg.getSectionId(),
                        "inputParam", cfg.getInputParamName(),
                        "requestInput", inputParams,
                        "response", responseNode
                );
                // ✅ FULL RESPONSE AUDIT (SAFE JSON)
                audit.audit(
                        "CONTAINER_DATA_EXECUTED",
                        session.getConversationId(),
                        mapper.writeValueAsString(jsonMap)
                );

            } catch (Exception e) {
                Map<String, Object> errorJsonMap = Map.of(
                        "containerId", cfg.getContainerId(),
                        "error", e.getMessage()
                );
                audit.audit(
                        "CONTAINER_DATA_FAILED",
                        session.getConversationId(),
                        mapper.writeValueAsString(errorJsonMap)
                );
            }
        }

        if (!containerRoot.isEmpty()) {

            // attach to session
            session.setContainerDataJson(containerRoot.toString());

            // merge into conversation context
            try {
                ObjectNode ctx = (ObjectNode) mapper.readTree(session.getContextJson());
                ctx.set("container_data", containerRoot);
                session.setContextJson(mapper.writeValueAsString(ctx));
                session.getConversation().setContextJson(session.getContextJson());
            } catch (Exception ignore) {
                // context merge failure should not break pipeline
            }

            audit.audit(
                    "CONTAINER_DATA_ATTACHED",
                    session.getConversationId(),
                    containerRoot.toString()
            );
        }

        return new StepResult.Continue();
    }
}
