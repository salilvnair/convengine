package com.github.salilvnair.convengine.engine.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.util.JsonUtil;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DefaultConvEngineSchemaResolver implements ConvEngineSchemaResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern XML_ELEMENT_TAG = Pattern.compile("<(?:[A-Za-z_][\\w.-]*:)?element\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_ATTR = Pattern.compile("\\b([A-Za-z_:][A-Za-z0-9_.:-]*)\\s*=\\s*\"([^\"]*)\"");

    @Override
    public boolean supports(String schemaDefinition) {
        return schemaDefinition != null && !schemaDefinition.isBlank();
    }

    @Override
    public Map<String, Object> schemaFieldDetails(String schemaDefinition) {
        if (isXml(schemaDefinition)) {
            return xmlFieldDetails(schemaDefinition);
        }
        return jsonFieldDetails(schemaDefinition);
    }

    @Override
    public List<String> missingRequiredFields(String schemaDefinition, String contextJson) {
        if (isXml(schemaDefinition)) {
            return missingRequiredFieldsByNames(xmlRequiredNames(schemaDefinition), contextJson);
        }
        return missingRequiredFieldsByNames(jsonRequiredNames(schemaDefinition), contextJson);
    }

    @Override
    public boolean isSchemaComplete(String schemaDefinition, String contextJson) {
        if (isXml(schemaDefinition)) {
            return missingRequiredFields(schemaDefinition, contextJson).isEmpty();
        }
        return JsonUtil.isSchemaComplete(schemaDefinition, contextJson);
    }

    @Override
    public boolean hasAnySchemaValue(String contextJson, String schemaDefinition) {
        if (isXml(schemaDefinition)) {
            Set<String> names = xmlAllNames(schemaDefinition);
            JsonNode context = JsonUtil.parseOrNull(safeJson(contextJson));
            if (!context.isObject()) {
                return false;
            }
            for (String name : names) {
                JsonNode value = context.path(name);
                if (hasMeaningfulValue(jsonNodeToObject(value))) {
                    return true;
                }
            }
            return false;
        }
        return JsonUtil.hasAnySchemaValue(safeJson(contextJson), schemaDefinition);
    }

    @Override
    public String seedContextFromInputParams(String currentContextJson, Map<String, Object> inputParams, String schemaDefinition) {
        Set<String> fields = isXml(schemaDefinition) ? xmlAllNames(schemaDefinition) : jsonPropertyNames(schemaDefinition);
        try {
            JsonNode existingContext = JsonUtil.parseOrNull(safeJson(currentContextJson));
            ObjectNode contextNode = existingContext != null && existingContext.isObject()
                    ? ((ObjectNode) existingContext.deepCopy())
                    : MAPPER.createObjectNode();
            for (String field : fields) {
                Object currentValue = jsonNodeToObject(contextNode.path(field));
                Object inputValue = inputParams == null ? null : inputParams.get(field);
                if (!hasMeaningfulValue(currentValue) && hasMeaningfulValue(inputValue)) {
                    contextNode.set(field, MAPPER.valueToTree(inputValue));
                }
            }
            return MAPPER.writeValueAsString(contextNode);
        } catch (Exception e) {
            return safeJson(currentContextJson);
        }
    }

    @Override
    public String sanitizeExtractedJson(String extractedJson) {
        try {
            JsonNode node = JsonUtil.parseOrNull(extractedJson);
            if (!node.isObject()) {
                return "{}";
            }
            ObjectNode cleaned = MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value == null || value.isNull()) {
                    return;
                }
                if (value.isTextual() && value.asText().isBlank()) {
                    return;
                }
                if (value.isArray() && value.isEmpty()) {
                    return;
                }
                cleaned.set(entry.getKey(), value);
            });
            return MAPPER.writeValueAsString(cleaned);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public String mergeContextJson(String currentContextJson, String extractedJson) {
        return JsonUtil.merge(safeJson(currentContextJson), sanitizeExtractedJson(extractedJson));
    }

    @Override
    public ConvEngineSchemaComputation compute(String schemaDefinition, String mergedContextJson, Map<String, Object> schemaFieldDetails) {
        boolean complete = isSchemaComplete(schemaDefinition, mergedContextJson);
        boolean hasAnyValue = hasAnySchemaValue(mergedContextJson, schemaDefinition);
        List<String> missingFields = missingRequiredFields(schemaDefinition, mergedContextJson);
        Map<String, Object> options = missingFieldOptions(missingFields, schemaFieldDetails);
        return new ConvEngineSchemaComputation(complete, hasAnyValue, missingFields, options);
    }

    private Map<String, Object> jsonFieldDetails(String schemaJson) {
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            JsonNode schema = JsonUtil.parseOrNull(schemaJson);
            JsonNode properties = schema.path("properties");
            if (!properties.isObject()) {
                return details;
            }
            properties.fieldNames().forEachRemaining(name -> {
                JsonNode node = properties.path(name);
                Map<String, Object> fieldDetails = new LinkedHashMap<>();
                fieldDetails.put("description", node.path("description").isMissingNode() ? null : node.path("description").asText(null));
                fieldDetails.put("type", node.path("type").isMissingNode() ? null : node.path("type"));
                List<Object> enumValues = new ArrayList<>();
                JsonNode enumNode = node.path("enum");
                if (enumNode.isArray()) {
                    enumNode.forEach(v -> {
                        if (!v.isNull()) {
                            enumValues.add(v.isTextual() ? v.asText() : v.toString());
                        }
                    });
                }
                fieldDetails.put("enumOptions", enumValues);
                details.put(name, fieldDetails);
            });
            return details;
        } catch (Exception e) {
            return details;
        }
    }

    private Set<String> jsonPropertyNames(String schemaJson) {
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonNode schema = JsonUtil.parseOrNull(schemaJson);
            JsonNode properties = schema.path("properties");
            if (!properties.isObject()) {
                return names;
            }
            for (Iterator<String> it = properties.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
        } catch (Exception ignored) {
            // no-op
        }
        return names;
    }

    private Set<String> jsonRequiredNames(String schemaJson) {
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonNode schema = JsonUtil.parseOrNull(schemaJson);
            JsonNode required = schema.path("required");
            if (!required.isArray()) {
                return names;
            }
            required.forEach(req -> names.add(req.asText()));
        } catch (Exception ignored) {
            // no-op
        }
        return names;
    }

    private Map<String, Object> xmlFieldDetails(String xsdOrXmlSchema) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (XmlElementSpec element : parseXmlElements(xsdOrXmlSchema)) {
            Map<String, Object> fieldDetails = new LinkedHashMap<>();
            fieldDetails.put("description", null);
            fieldDetails.put("type", element.type);
            fieldDetails.put("enumOptions", List.of());
            details.put(element.name, fieldDetails);
        }
        return details;
    }

    private Set<String> xmlAllNames(String schemaDefinition) {
        Set<String> names = new LinkedHashSet<>();
        for (XmlElementSpec element : parseXmlElements(schemaDefinition)) {
            names.add(element.name);
        }
        return names;
    }

    private Set<String> xmlRequiredNames(String schemaDefinition) {
        Set<String> names = new LinkedHashSet<>();
        for (XmlElementSpec element : parseXmlElements(schemaDefinition)) {
            if (element.required) {
                names.add(element.name);
            }
        }
        return names;
    }

    private List<String> missingRequiredFieldsByNames(Set<String> requiredNames, String contextJson) {
        List<String> missing = new ArrayList<>();
        if (requiredNames.isEmpty()) {
            return missing;
        }
        JsonNode data = JsonUtil.parseOrNull(safeJson(contextJson));
        if (!data.isObject()) {
            return new ArrayList<>(requiredNames);
        }
        for (String field : requiredNames) {
            JsonNode value = data.path(field);
            if (!hasMeaningfulValue(jsonNodeToObject(value))) {
                missing.add(field);
            }
        }
        return missing;
    }

    private boolean isXml(String schemaDefinition) {
        if (schemaDefinition == null) {
            return false;
        }
        String value = schemaDefinition.trim();
        return value.startsWith("<");
    }

    private String safeJson(String json) {
        return (json == null || json.isBlank()) ? "{}" : json;
    }

    private Map<String, Object> missingFieldOptions(List<String> missingFields, Map<String, Object> fieldDetails) {
        Map<String, Object> options = new LinkedHashMap<>();
        for (String field : missingFields) {
            Object raw = fieldDetails.get(field);
            if (!(raw instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Object enumOptions = rawMap.get("enumOptions");
            if (enumOptions instanceof List<?> enumList && !enumList.isEmpty()) {
                options.put(field, enumList);
            }
        }
        return options;
    }

    private Object jsonNodeToObject(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return MAPPER.convertValue(node, Object.class);
    }

    private boolean hasMeaningfulValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isBlank();
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    private List<XmlElementSpec> parseXmlElements(String schemaDefinition) {
        List<XmlElementSpec> result = new ArrayList<>();
        if (schemaDefinition == null || schemaDefinition.isBlank()) {
            return result;
        }
        Matcher tagMatcher = XML_ELEMENT_TAG.matcher(schemaDefinition);
        while (tagMatcher.find()) {
            String attrsRaw = tagMatcher.group(1);
            Map<String, String> attrs = new LinkedHashMap<>();
            Matcher attrMatcher = XML_ATTR.matcher(attrsRaw);
            while (attrMatcher.find()) {
                String key = localName(attrMatcher.group(1));
                attrs.put(key, attrMatcher.group(2));
            }
            String name = attrs.get("name");
            if (name == null || name.isBlank()) {
                continue;
            }
            String minOccurs = attrs.get("minOccurs");
            boolean required = minOccurs == null || !"0".equals(minOccurs.trim());
            String type = attrs.get("type");
            result.add(new XmlElementSpec(name.trim(), required, type));
        }
        return result;
    }

    private String localName(String qName) {
        int idx = qName == null ? -1 : qName.indexOf(':');
        return idx >= 0 ? qName.substring(idx + 1) : qName;
    }

    private record XmlElementSpec(String name, boolean required, String type) {
    }
}
