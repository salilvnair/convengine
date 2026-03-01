package com.github.salilvnair.convengine.transport.verbose.resolve.provider;

import com.github.salilvnair.convengine.api.dto.VerboseStreamPayload;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import com.github.salilvnair.convengine.entity.CeVerbose;
import com.github.salilvnair.convengine.engine.constants.ConvEngineSyntaxConstants;
import com.github.salilvnair.convengine.engine.constants.ConvEngineValue;
import com.github.salilvnair.convengine.engine.constants.MatchTypeConstants;
import com.github.salilvnair.convengine.engine.constants.ProcessingStatusConstants;
import com.github.salilvnair.convengine.template.ThymeleafTemplateRenderer;
import com.github.salilvnair.convengine.transport.verbose.VerboseConstants;
import com.github.salilvnair.convengine.transport.verbose.resolve.MessageResolver;
import com.github.salilvnair.convengine.transport.verbose.resolve.VerboseResolveRequest;
import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
@Order(100)
@RequiredArgsConstructor
public class DbVerboseMessageResolver implements MessageResolver {

    private final StaticConfigurationCacheService staticCacheService;
    private final ThymeleafTemplateRenderer templateRenderer;

    @Override
    public Optional<VerboseStreamPayload> resolve(VerboseResolveRequest request) {
        if (request == null || isBlank(request.intent()) || isBlank(request.state())
                || isBlank(request.stepName()) || isBlank(request.determinant())) {
            return Optional.empty();
        }

        List<CeVerbose> candidates = staticCacheService.findEligibleVerboseMessages(request.intent(), request.state()).stream()
                .filter(v -> matchesDeterminant(v, request.determinant()))
                .filter(v -> matchesStep(v, request))
                .filter(v -> matchesRule(v, request.ruleId()))
                .filter(v -> matchesTool(v, request.toolCode()))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        CeVerbose selected = candidates.stream()
                .max((a, b) -> Integer.compare(score(a, request), score(b, request)))
                .orElse(null);
        if (selected == null) {
            return Optional.empty();
        }

        String rawText = request.error()
                ? firstNonBlank(selected.getErrorMessage(), selected.getMessage())
                : firstNonBlank(selected.getMessage(), selected.getErrorMessage());
        if (isBlank(rawText)) {
            return Optional.empty();
        }
        String renderedText = templateRenderer.render(rawText, request.session(), request.metadata());
        String renderedMessage = templateRenderer.render(selected.getMessage(), request.session(), request.metadata());
        String renderedErrorMessage = templateRenderer.render(selected.getErrorMessage(), request.session(),
                request.metadata());
        if (isBlank(renderedText)) {
            return Optional.empty();
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        metadata.putIfAbsent(VerboseConstants.METADATA_SEVERITY,
                request.error() ? ProcessingStatusConstants.ERROR : ProcessingStatusConstants.INFO);
        metadata.putIfAbsent(VerboseConstants.METADATA_THEME,
                request.error() ? VerboseConstants.THEME_DANGER : VerboseConstants.THEME_PROGRESS);
        metadata.putIfAbsent(VerboseConstants.METADATA_ICON,
                request.error() ? VerboseConstants.ICON_WARNING : VerboseConstants.ICON_SPARK);

        return Optional.of(
                VerboseStreamPayload.builder()
                        .verboseId(selected.getVerboseId())
                        .eventType(VerboseConstants.EVENT_TYPE_VERBOSE_PROGRESS)
                        .stepName(request.stepName())
                        .determinant(request.determinant())
                        .intent(request.intent())
                        .state(request.state())
                        .ruleId(request.ruleId())
                        .toolCode(request.toolCode())
                        .level(request.error() ? ProcessingStatusConstants.ERROR : ProcessingStatusConstants.INFO)
                        .text(renderedText)
                        .message(renderedMessage)
                        .errorMessage(renderedErrorMessage)
                        .metadata(metadata)
                        .build());
    }

    private boolean matchesRule(CeVerbose verbose, Long ruleId) {
        if (verbose.getRuleId() == null) {
            return true;
        }
        return ruleId != null && verbose.getRuleId().equals(ruleId);
    }

    private boolean matchesDeterminant(CeVerbose verbose, String determinant) {
        if (isBlank(verbose.getDeterminant())) {
            return true;
        }
        return ConvEngineValue.ANY.equalsIgnoreCase(verbose.getDeterminant().trim())
                || verbose.getDeterminant().trim().equalsIgnoreCase(determinant.trim());
    }

    private boolean matchesStep(CeVerbose verbose, VerboseResolveRequest request) {
        if (isBlank(verbose.getStepValue())) {
            return true;
        }
        String match = normalizeStepMatch(verbose.getStepMatch());
        String value = verbose.getStepValue().trim();
        return switch (match) {
            case MatchTypeConstants.REGEX -> matchesRegex(value, request.stepName());
            case MatchTypeConstants.JSON_PATH -> matchesJsonPath(value, request.metadata());
            default -> value.equalsIgnoreCase(request.stepName().trim())
                    || ConvEngineValue.ANY.equalsIgnoreCase(value);
        };
    }

    private boolean matchesRegex(String expression, String value) {
        try {
            return Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(value).find();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchesJsonPath(String expression, Map<String, Object> metadata) {
        try {
            Object data = metadata == null ? Map.of() : metadata;
            Object value = JsonPath.read(data, expression);
            if (value == null) {
                return false;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            return !String.valueOf(value).isBlank()
                    && !ConvEngineSyntaxConstants.BOOLEAN_FALSE.equalsIgnoreCase(String.valueOf(value));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchesTool(CeVerbose verbose, String toolCode) {
        if (isBlank(verbose.getToolCode())) {
            return true;
        }
        return !isBlank(toolCode) && verbose.getToolCode().trim().equalsIgnoreCase(toolCode.trim());
    }

    private int score(CeVerbose verbose, VerboseResolveRequest request) {
        int score = 0;
        if (!isBlank(verbose.getIntentCode()) && verbose.getIntentCode().equalsIgnoreCase(request.intent())) {
            score += 400;
        }
        if (!isBlank(verbose.getStateCode()) && verbose.getStateCode().equalsIgnoreCase(request.state())) {
            score += 300;
        }
        if (!isBlank(verbose.getDeterminant()) && !ConvEngineValue.ANY.equalsIgnoreCase(verbose.getDeterminant())
                && verbose.getDeterminant().equalsIgnoreCase(request.determinant())) {
            score += 250;
        }
        String stepMatch = normalizeStepMatch(verbose.getStepMatch());
        if (MatchTypeConstants.EXACT.equals(stepMatch) && !isBlank(verbose.getStepValue())
                && verbose.getStepValue().equalsIgnoreCase(request.stepName())) {
            score += 225;
        } else if (MatchTypeConstants.REGEX.equals(stepMatch) && !isBlank(verbose.getStepValue())) {
            score += 150;
        } else if (MatchTypeConstants.JSON_PATH.equals(stepMatch) && !isBlank(verbose.getStepValue())) {
            score += 100;
        }
        if (verbose.getRuleId() != null && request.ruleId() != null && verbose.getRuleId().equals(request.ruleId())) {
            score += 200;
        }
        if (!isBlank(verbose.getToolCode()) && !isBlank(request.toolCode())
                && verbose.getToolCode().equalsIgnoreCase(request.toolCode())) {
            score += 150;
        }
        Integer priority = verbose.getPriority();
        score -= (priority == null ? Integer.MAX_VALUE / 4 : priority);
        return score;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalizeStepMatch(String stepMatch) {
        if (isBlank(stepMatch)) {
            return MatchTypeConstants.EXACT;
        }
        String normalized = stepMatch.trim().toUpperCase(Locale.ROOT);
        if (!MatchTypeConstants.EXACT.equals(normalized)
                && !MatchTypeConstants.REGEX.equals(normalized)
                && !MatchTypeConstants.JSON_PATH.equals(normalized)) {
            return MatchTypeConstants.EXACT;
        }
        return normalized;
    }
}
