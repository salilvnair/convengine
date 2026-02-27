package com.github.salilvnair.convengine.transport.verbose;

import com.github.salilvnair.convengine.engine.session.EngineSession;
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
        if (session == null || session.getConversationId() == null) {
            return;
        }
        Map<String, Object> safeMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            safeMetadata.putAll(metadata);
        }
        safeMetadata.putIfAbsent("conversationId", String.valueOf(session.getConversationId()));
        safeMetadata.putIfAbsent("stepName", stepName);
        safeMetadata.putIfAbsent("determinant", determinant);

        VerboseResolveRequest request = VerboseResolveRequest.builder()
                .intent(session.getIntent())
                .state(session.getState())
                .stepName(stepName)
                .determinant(determinant)
                .ruleId(ruleId)
                .toolCode(toolCode)
                .error(error)
                .metadata(safeMetadata)
                .build();

        Optional.ofNullable(resolverFactory.resolve(request).orElse(null))
                .ifPresentOrElse(
                        resolved -> dispatcher.dispatch(session.getConversationId(), resolved),
                        () -> {
                            if (error) {
                                dispatcher.dispatch(session.getConversationId(),
                                        com.github.salilvnair.convengine.api.dto.VerboseStreamPayload.builder()
                                                .eventType("VERBOSE_PROGRESS")
                                                .stepName(stepName)
                                                .determinant(determinant)
                                                .intent(session.getIntent())
                                                .state(session.getState())
                                                .ruleId(ruleId)
                                                .toolCode(toolCode)
                                                .level("ERROR")
                                                .text("Something went wrong while processing " + stepName + ".")
                                                .metadata(safeMetadata)
                                                .build());
                            }
                        });
    }
}
