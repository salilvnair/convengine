package com.github.salilvnair.convengine.engine.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.constants.CorrectionConstants;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.constants.ConvEngineSyntaxConstants;
import com.github.salilvnair.convengine.engine.constants.ConvEngineValue;
import com.github.salilvnair.convengine.engine.constants.RoutingDecisionConstants;
import com.github.salilvnair.convengine.engine.dialogue.DialogueAct;
import com.github.salilvnair.convengine.engine.pipeline.EngineStep;
import com.github.salilvnair.convengine.engine.pipeline.StepResult;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunAfter;
import com.github.salilvnair.convengine.engine.pipeline.annotation.MustRunBefore;
import com.github.salilvnair.convengine.engine.pipeline.annotation.RequiresConversationPersisted;
import com.github.salilvnair.convengine.engine.schema.ConvEngineSchemaComputation;
import com.github.salilvnair.convengine.engine.schema.ConvEngineSchemaResolver;
import com.github.salilvnair.convengine.engine.schema.ConvEngineSchemaResolverFactory;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeOutputSchema;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;
import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
@RequiresConversationPersisted
@MustRunAfter(InteractionPolicyStep.class)
@MustRunBefore({IntentResolutionStep.class, SchemaExtractionStep.class})
public class CorrectionStep implements EngineStep {

    private static final Pattern EXPLICIT_SET_PATTERN = Pattern.compile(
            "(?i)(?:change|set|update|make)\\s+(?:the\\s+)?(.+?)\\s+(?:to|as)\\s+(.+)$");
    private static final Pattern RETRY_PATTERN = Pattern.compile(
            "(?i)^\\s*(retry|try again|retry please|please retry|again)\\s*[.!?]*\\s*$");

    private final StaticConfigurationCacheService staticCacheService;
    private final ConvEngineSchemaResolverFactory schemaResolverFactory;
    private final AuditService audit;
    private final VerboseMessagePublisher verbosePublisher;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public StepResult execute(EngineSession session) {
        resetTurnRouting(session);

        DialogueAct dialogueAct = parseDialogueAct(session.inputParamAsString(ConvEngineInputParamKey.DIALOGUE_ACT));
        if (dialogueAct == DialogueAct.ANSWER) {
            session.putInputParam(ConvEngineInputParamKey.ROUTING_DECISION, RoutingDecisionConstants.HANDLE_AS_ANSWER);
        }

        Optional<CeOutputSchema> schemaOptional = resolveSchema(session);
        if (schemaOptional.isEmpty()) {
            return new StepResult.Continue();
        }

        CeOutputSchema schema = schemaOptional.get();
        ConvEngineSchemaResolver resolver = schemaResolverFactory.get(schema.getJsonSchema());
        Map<String, Object> schemaFieldDetails = resolver.schemaFieldDetails(schema.getJsonSchema());
        ConvEngineSchemaComputation current = resolver.compute(schema.getJsonSchema(), safeJson(session.getContextJson()),
                schemaFieldDetails);

        Optional<CePromptTemplate> interactionTemplate = resolveInteractionTemplate(session);
        boolean allowsAffirm = interactionTemplate.map(CePromptTemplate::allowsAffirmAction).orElse(false);
        boolean allowsEdit = interactionTemplate.map(CePromptTemplate::allowsEditAction).orElse(false);
        boolean allowsRetry = interactionTemplate.map(CePromptTemplate::allowsRetryAction).orElse(false);

        if (allowsRetry && isRetryRequest(session.getUserText())) {
            session.putInputParam(ConvEngineInputParamKey.ROUTING_DECISION, RoutingDecisionConstants.RETRY_IN_PLACE);
            session.putInputParam(ConvEngineInputParamKey.SKIP_SCHEMA_EXTRACTION, true);
            session.putInputParam(ConvEngineInputParamKey.SKIP_INTENT_RESOLUTION, true);
            publish(session, CorrectionConstants.EVENT_RETRY_REQUESTED, Map.of(
                    ConvEnginePayloadKey.INTENT, session.getIntent(),
                    ConvEnginePayloadKey.STATE, session.getState(),
                    ConvEnginePayloadKey.ROUTING_DECISION, RoutingDecisionConstants.RETRY_IN_PLACE));
            return new StepResult.Continue();
        }

        if (allowsAffirm && dialogueAct == DialogueAct.AFFIRM && current.schemaComplete()) {
            applyComputedSchema(session, schema, current, schemaFieldDetails);
            session.putInputParam(ConvEngineInputParamKey.ROUTING_DECISION, RoutingDecisionConstants.PROCEED_CONFIRMED);
            session.putInputParam(ConvEngineInputParamKey.SKIP_SCHEMA_EXTRACTION, true);
            session.putInputParam(ConvEngineInputParamKey.SKIP_INTENT_RESOLUTION, true);
            publish(session, CorrectionConstants.EVENT_CONFIRM_ACCEPT, Map.of(
                    ConvEnginePayloadKey.INTENT, session.getIntent(),
                    ConvEnginePayloadKey.STATE, session.getState(),
                    ConvEnginePayloadKey.ROUTING_DECISION, RoutingDecisionConstants.PROCEED_CONFIRMED));
            return new StepResult.Continue();
        }

        if (allowsEdit && dialogueAct == DialogueAct.EDIT) {
            PatchResult patchResult = tryApplySingleFieldPatch(session, schema, resolver, schemaFieldDetails);
            if (patchResult.applied()) {
                session.putInputParam(ConvEngineInputParamKey.ROUTING_DECISION, RoutingDecisionConstants.APPLY_CORRECTION);
                session.putInputParam(ConvEngineInputParamKey.SKIP_SCHEMA_EXTRACTION, true);
                session.putInputParam(ConvEngineInputParamKey.SKIP_INTENT_RESOLUTION, true);
                session.putInputParam(ConvEngineInputParamKey.CORRECTION_APPLIED, true);
                session.putInputParam(ConvEngineInputParamKey.CORRECTION_TARGET_FIELD, patchResult.field());
                publish(session, CorrectionConstants.EVENT_CORRECTION_PATCH_APPLIED, Map.of(
                        ConvEnginePayloadKey.FIELD, patchResult.field(),
                        ConvEnginePayloadKey.VALUE, patchResult.value(),
                        ConvEnginePayloadKey.INTENT, session.getIntent(),
                        ConvEnginePayloadKey.STATE, session.getState(),
                        ConvEnginePayloadKey.ROUTING_DECISION, RoutingDecisionConstants.APPLY_CORRECTION));
            }
        }

        return new StepResult.Continue();
    }

    private void resetTurnRouting(EngineSession session) {
        session.putInputParam(ConvEngineInputParamKey.ROUTING_DECISION, RoutingDecisionConstants.CONTINUE_STANDARD_FLOW);
        session.putInputParam(ConvEngineInputParamKey.SKIP_SCHEMA_EXTRACTION, false);
        session.putInputParam(ConvEngineInputParamKey.CORRECTION_APPLIED, false);
        session.putInputParam(ConvEngineInputParamKey.CORRECTION_TARGET_FIELD, null);
    }

    private Optional<CeOutputSchema> resolveSchema(EngineSession session) {
        if (session.getResolvedSchema() != null) {
            return Optional.of(session.getResolvedSchema());
        }
        String intent = session.getIntent();
        String state = session.getState();
        if (intent == null || intent.isBlank()) {
            return Optional.empty();
        }
        return resolveSchemaByPersistedId(session)
                .or(() -> staticCacheService.findFirstOutputSchema(intent, state))
                .or(() -> staticCacheService.findFirstOutputSchema(intent, ConvEngineValue.ANY));
    }

    private Optional<CeOutputSchema> resolveSchemaByPersistedId(EngineSession session) {
        Object schemaIdValue = session.getInputParams().get(ConvEngineInputParamKey.SCHEMA_ID);
        Long schemaId = toLong(schemaIdValue);
        if (schemaId == null) {
            return Optional.empty();
        }
        return staticCacheService.findOutputSchemaById(schemaId);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Optional<CePromptTemplate> resolveInteractionTemplate(EngineSession session) {
        String intent = session.getIntent();
        String state = session.getState();
        if (intent == null || intent.isBlank() || state == null || state.isBlank()) {
            return Optional.empty();
        }
        return staticCacheService.findInteractionTemplate(intent, state);
    }

    private boolean isRetryRequest(String userText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        return RETRY_PATTERN.matcher(userText).matches();
    }

    private DialogueAct parseDialogueAct(String raw) {
        if (raw == null || raw.isBlank()) {
            return DialogueAct.NEW_REQUEST;
        }
        try {
            return DialogueAct.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return DialogueAct.NEW_REQUEST;
        }
    }

    private PatchResult tryApplySingleFieldPatch(EngineSession session,
            CeOutputSchema schema,
            ConvEngineSchemaResolver resolver,
            Map<String, Object> schemaFieldDetails) {
        String userText = session.getUserText() == null ? "" : session.getUserText().trim();
        if (userText.isBlank()) {
            return PatchResult.notApplied();
        }

        Map<String, FieldAlias> aliases = buildAliases(schemaFieldDetails);
        Matcher matcher = EXPLICIT_SET_PATTERN.matcher(userText);
        String requestedField = null;
        String requestedValue = null;

        if (matcher.matches()) {
            requestedField = matcher.group(1);
            requestedValue = matcher.group(2);
        } else {
            FieldAlias best = aliases.values().stream()
                    .filter(alias -> containsNormalized(userText, alias.lookupText()))
                    .max(Comparator.comparingInt(alias -> alias.lookupText().length()))
                    .orElse(null);
            if (best != null) {
                requestedField = best.label();
                int idx = userText.toLowerCase(Locale.ROOT).indexOf(best.lookupText());
                if (idx >= 0) {
                    requestedValue = userText.substring(idx + best.lookupText().length()).trim();
                }
            }
        }

        if (requestedField == null || requestedField.isBlank() || requestedValue == null || requestedValue.isBlank()) {
            return PatchResult.notApplied();
        }

        String resolvedField = resolveFieldKey(requestedField, aliases);
        if (resolvedField == null) {
            return PatchResult.notApplied();
        }

        Object typedValue = coerceValue(resolvedField, requestedValue, schemaFieldDetails);
        if (typedValue == null && !ConvEngineSyntaxConstants.NULL_LITERAL.equalsIgnoreCase(requestedValue.trim())) {
            return PatchResult.notApplied();
        }

        try {
            ObjectNode context = ensureContextObject(session.getContextJson());
            context.set(resolvedField, mapper.valueToTree(typedValue));
            String updatedContext = mapper.writeValueAsString(context);
            session.setContextJson(updatedContext);
            session.getConversation().setContextJson(updatedContext);

            ConvEngineSchemaComputation computation = resolver.compute(schema.getJsonSchema(), updatedContext,
                    schemaFieldDetails);
            applyComputedSchema(session, schema, computation, schemaFieldDetails);
            return new PatchResult(true, resolvedField, typedValue);
        } catch (Exception e) {
            return PatchResult.notApplied();
        }
    }

    private Map<String, FieldAlias> buildAliases(Map<String, Object> schemaFieldDetails) {
        Map<String, FieldAlias> aliases = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : schemaFieldDetails.entrySet()) {
            String key = entry.getKey();
            String lookup = normalizeKey(key);
            String label = key;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                Object description = map.get("description");
                if (description instanceof String descriptionText && !descriptionText.isBlank()) {
                    String normalizedDescription = normalizeKey(descriptionText);
                    if (normalizedDescription.length() > lookup.length()) {
                        lookup = normalizedDescription;
                        label = descriptionText;
                    }
                }
            }
            aliases.put(key, new FieldAlias(key, lookup, label));
        }
        return aliases;
    }

    private String resolveFieldKey(String requestedField, Map<String, FieldAlias> aliases) {
        String normalizedRequested = normalizeKey(requestedField);
        return aliases.values().stream()
                .filter(alias -> alias.lookupText().contains(normalizedRequested)
                        || normalizedRequested.contains(alias.lookupText()))
                .max(Comparator.comparingInt(alias -> alias.lookupText().length()))
                .map(FieldAlias::fieldKey)
                .orElse(null);
    }

    private boolean containsNormalized(String raw, String normalizedNeedle) {
        return normalizeKey(raw).contains(normalizedNeedle);
    }

    private String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "");
    }

    private Object coerceValue(String fieldKey, String requestedValue, Map<String, Object> schemaFieldDetails) {
        String raw = requestedValue == null ? "" : requestedValue.trim();
        if (raw.isBlank()) {
            return "";
        }
        raw = raw.replaceAll("^[=:,\\s]+", "").trim();
        raw = raw.replaceAll("[.?!]+$", "").trim();

        Object details = schemaFieldDetails.get(fieldKey);
        String type = null;
        if (details instanceof Map<?, ?> map) {
            Object typeNode = map.get("type");
            if (typeNode instanceof JsonNode jsonNode) {
                if (jsonNode.isTextual()) {
                    type = jsonNode.asText();
                } else if (jsonNode.isArray() && !jsonNode.isEmpty()) {
                    type = jsonNode.get(0).asText(null);
                }
            } else if (typeNode != null) {
                type = String.valueOf(typeNode);
            }
        }

        if ("integer".equalsIgnoreCase(type)) {
            String digits = raw.replaceAll("[^0-9-]", "");
            if (digits.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if ("number".equalsIgnoreCase(type)) {
            String digits = raw.replaceAll("[^0-9.-]", "");
            if (digits.isBlank()) {
                return null;
            }
            try {
                return Double.parseDouble(digits);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if ("boolean".equalsIgnoreCase(type)) {
            if (ConvEngineSyntaxConstants.BOOLEAN_TRUE.equalsIgnoreCase(raw)
                    || ConvEngineSyntaxConstants.YES_LITERAL.equalsIgnoreCase(raw)) {
                return true;
            }
            if (ConvEngineSyntaxConstants.BOOLEAN_FALSE.equalsIgnoreCase(raw)
                    || ConvEngineSyntaxConstants.NO_LITERAL.equalsIgnoreCase(raw)) {
                return false;
            }
            return null;
        }
        return raw;
    }

    private ObjectNode ensureContextObject(String currentContextJson) throws Exception {
        JsonNode node = JsonUtil.parseOrNull(safeJson(currentContextJson));
        if (node instanceof ObjectNode objectNode) {
            return (ObjectNode) objectNode.deepCopy();
        }
        return mapper.createObjectNode();
    }

    private String safeJson(String raw) {
        return raw == null || raw.isBlank() ? "{}" : raw;
    }

    private void applyComputedSchema(EngineSession session,
            CeOutputSchema schema,
            ConvEngineSchemaComputation computation,
            Map<String, Object> schemaFieldDetails) {
        session.setResolvedSchema(schema);
        session.setSchemaComplete(computation.schemaComplete());
        session.setSchemaHasAnyValue(computation.hasAnySchemaValue());
        session.setMissingRequiredFields(computation.missingFields());
        session.setMissingFieldOptions(computation.missingFieldOptions());
        if (computation.schemaComplete()) {
            session.unlockIntent();
        } else {
            session.lockIntent(ConvEngineValue.SCHEMA_INCOMPLETE);
        }
        session.putInputParam(ConvEngineInputParamKey.MISSING_FIELDS, computation.missingFields());
        session.putInputParam(ConvEngineInputParamKey.MISSING_FIELD_OPTIONS, computation.missingFieldOptions());
        session.putInputParam(ConvEngineInputParamKey.SCHEMA_FIELD_DETAILS, schemaFieldDetails);
        session.putInputParam(ConvEngineInputParamKey.SCHEMA_DESCRIPTION,
                schema.getDescription() == null ? "" : schema.getDescription());
        session.putInputParam(ConvEngineInputParamKey.SCHEMA_ID, schema.getSchemaId());
        session.addPromptTemplateVars();
    }

    private void publish(EngineSession session, String determinant, Map<String, Object> metadata) {
        audit.audit(CorrectionConstants.AUDIT_STAGE_PREFIX + determinant, session.getConversationId(), metadata);
        verbosePublisher.publish(session, CorrectionConstants.STEP_NAME, determinant, null, null, false, metadata);
    }

    private record FieldAlias(String fieldKey, String lookupText, String label) {
    }

    private record PatchResult(boolean applied, String field, Object value) {
        private static PatchResult notApplied() {
            return new PatchResult(false, null, null);
        }
    }
}
