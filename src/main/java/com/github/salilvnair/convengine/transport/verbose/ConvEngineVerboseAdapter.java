package com.github.salilvnair.convengine.transport.verbose;

import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;
import com.github.salilvnair.convengine.engine.constants.ProcessingStatusConstants;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.template.ThymeleafTemplateRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ConvEngineVerboseAdapter {

    private final VerboseMessagePublisher verboseMessagePublisher;
    private final VerboseEventDispatcher verboseEventDispatcher;
    private final ThymeleafTemplateRenderer templateRenderer;

    public void publish(EngineSession session, String source, String determinant) {
        publish(session, source, determinant, false, null, null, null);
    }

    public void publish(EngineSession session, Object source, String determinant) {
        publish(session, resolveSource(source), determinant);
    }

    public void publish(EngineSession session, String source, String determinant, Map<String, Object> metadata) {
        publish(session, source, determinant, false, null, metadata, null);
    }

    public void publish(EngineSession session, Object source, String determinant, Map<String, Object> metadata) {
        publish(session, resolveSource(source), determinant, metadata);
    }

    public void publishError(EngineSession session, String source, String determinant, Map<String, Object> metadata) {
        publish(session, source, determinant, true, null, metadata, null);
    }

    public void publishError(EngineSession session, Object source, String determinant, Map<String, Object> metadata) {
        publishError(session, resolveSource(source), determinant, metadata);
    }

    public void publishText(EngineSession session, String source, String determinant, String text) {
        publishText(session, source, determinant, text, false, null);
    }

    public void publishText(EngineSession session, Object source, String determinant, String text) {
        publishText(session, resolveSource(source), determinant, text);
    }

    public void publishText(
            EngineSession session,
            String source,
            String determinant,
            String text,
            boolean error,
            Map<String, Object> metadata) {
        if (session == null || session.getConversationId() == null) {
            return;
        }
        Map<String, Object> safeMetadata = safeMetadata(session, source, determinant, metadata);
        String renderedText = templateRenderer.render(text, session, safeMetadata);
        if (renderedText == null || renderedText.isBlank()) {
            return;
        }
        verboseEventDispatcher.dispatch(session.getConversationId(),
                VerboseStreamPayload.builder()
                        .eventType(VerboseConstants.EVENT_TYPE_VERBOSE_PROGRESS)
                        .stepName(source)
                        .determinant(determinant)
                        .intent(session.getIntent())
                        .state(session.getState())
                        .level(error ? ProcessingStatusConstants.ERROR : ProcessingStatusConstants.INFO)
                        .text(renderedText)
                        .message(renderedText)
                        .errorMessage(error ? renderedText : null)
                        .metadata(defaultMetadata(error, safeMetadata))
                        .build());
    }

    public void publishText(
            EngineSession session,
            Object source,
            String determinant,
            String text,
            boolean error,
            Map<String, Object> metadata) {
        publishText(session, resolveSource(source), determinant, text, error, metadata);
    }

    public void publish(
            EngineSession session,
            String source,
            String determinant,
            boolean error,
            Long ruleId,
            Map<String, Object> metadata,
            String toolCode) {
        if (session == null || session.getConversationId() == null) {
            return;
        }
        verboseMessagePublisher.publish(session, source, determinant, ruleId, toolCode, error,
                safeMetadata(session, source, determinant, metadata));
    }

    private Map<String, Object> safeMetadata(
            EngineSession session,
            String source,
            String determinant,
            Map<String, Object> metadata) {
        Map<String, Object> safeMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            safeMetadata.putAll(metadata);
        }
        safeMetadata.putIfAbsent("conversationId",
                session.getConversationId() == null ? null : String.valueOf(session.getConversationId()));
        safeMetadata.putIfAbsent("source", source);
        safeMetadata.putIfAbsent("determinant", determinant);
        return safeMetadata;
    }

    private Map<String, Object> defaultMetadata(boolean error, Map<String, Object> metadata) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (metadata != null) {
            resolved.putAll(metadata);
        }
        resolved.putIfAbsent(VerboseConstants.METADATA_SEVERITY,
                error ? ProcessingStatusConstants.ERROR : ProcessingStatusConstants.INFO);
        resolved.putIfAbsent(VerboseConstants.METADATA_THEME,
                error ? VerboseConstants.THEME_DANGER : VerboseConstants.THEME_PROGRESS);
        resolved.putIfAbsent(VerboseConstants.METADATA_ICON,
                error ? VerboseConstants.ICON_WARNING : VerboseConstants.ICON_SPARK);
        return resolved;
    }

    private String resolveSource(Object source) {
        if (source == null) {
            return null;
        }
        if (source instanceof String value) {
            return value;
        }
        return source.getClass().getSimpleName();
    }
}
