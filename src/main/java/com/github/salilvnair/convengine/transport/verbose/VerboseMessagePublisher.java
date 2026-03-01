package com.github.salilvnair.convengine.transport.verbose;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.constants.ProcessingStatusConstants;
import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;
import com.github.salilvnair.convengine.transport.verbose.resolve.MessageResolverFactory;
import com.github.salilvnair.convengine.transport.verbose.resolve.VerboseResolveRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class VerboseMessagePublisher {

    private final MessageResolverFactory resolverFactory;
    private final VerboseEventDispatcher dispatcher;

    public void publish(EngineSession session,
                        String stepName,
                        String determinant,
                        Long ruleId,
                        String toolCode,
                        boolean error,
                        Map<String, Object> metadata) {
        Optional<VerboseStreamPayload> resolved = resolve(session, stepName, determinant, ruleId, toolCode, error, metadata);
        if (session == null || session.getConversationId() == null) {
            return;
        }
        Map<String, Object> safeMetadata = safeMetadata(session, stepName, determinant, metadata);
        resolved.ifPresentOrElse(
                payload -> dispatcher.dispatch(session.getConversationId(), payload),
                () -> {
                    if (error) {
                        dispatcher.dispatch(session.getConversationId(),
                                com.github.salilvnair.convengine.api.dto.VerboseStreamPayload.builder()
                                        .eventType(VerboseConstants.EVENT_TYPE_VERBOSE_PROGRESS)
                                        .stepName(stepName)
                                        .determinant(determinant)
                                        .intent(session.getIntent())
                                        .state(session.getState())
                                        .ruleId(ruleId)
                                        .toolCode(toolCode)
                                        .level(ProcessingStatusConstants.ERROR)
                                        .text("Something went wrong while processing " + stepName + ".")
                                        .metadata(safeMetadata)
                                        .build());
                    }
                });
    }

    public Optional<VerboseStreamPayload> resolve(EngineSession session,
                                                  String stepName,
                                                  String determinant,
                                                  Long ruleId,
                                                  String toolCode,
                                                  boolean error,
                                                  Map<String, Object> metadata) {
        if (session == null || session.getConversationId() == null) {
            return Optional.empty();
        }
        Map<String, Object> safeMetadata = safeMetadata(session, stepName, determinant, metadata);

        VerboseResolveRequest request = VerboseResolveRequest.builder()
                .intent(session.getIntent())
                .state(session.getState())
                .stepName(stepName)
                .determinant(determinant)
                .ruleId(ruleId)
                .toolCode(toolCode)
                .session(session)
                .error(error)
                .metadata(safeMetadata)
                .build();

        return resolverFactory.resolve(request);
    }

    private Map<String, Object> safeMetadata(EngineSession session,
                                             String stepName,
                                             String determinant,
                                             Map<String, Object> metadata) {
        Map<String, Object> safeMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            safeMetadata.putAll(metadata);
        }
        safeMetadata.putIfAbsent("conversationId", String.valueOf(session.getConversationId()));
        safeMetadata.putIfAbsent("stepName", stepName);
        safeMetadata.putIfAbsent("determinant", determinant);
        return safeMetadata;
    }
}
