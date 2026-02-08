package com.github.salilvnair.convengine.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.Iterator;

@UtilityClass
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Create empty JSON object */
    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /** Parse JSON string safely (used for schema, MCP output, etc.) */
    public static JsonNode parseOrNull(String json) {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return NullNode.getInstance();
        }
    }

    /**
     * Convert any object into JSON string.
     */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize object to JSON", e);
        }
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        try {
            // Jackson returns a quoted JSON string → strip quotes
            String quoted = MAPPER.writeValueAsString(value);
            return quoted.substring(1, quoted.length() - 1);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to JSON-escape string", e);
        }
    }

    /**
     * Parse JSON string into target type.
     */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize JSON", e);
        }
    }


    public static String merge(String targetJson, String sourceJson) {

        try {
            JsonNode targetNode =
                    isEmpty(targetJson)
                            ? MAPPER.createObjectNode()
                            : MAPPER.readTree(targetJson);

            JsonNode sourceNode =
                    isEmpty(sourceJson)
                            ? MAPPER.createObjectNode()
                            : MAPPER.readTree(sourceJson);

            JsonNode merged = deepMerge(targetNode, sourceNode, false);
            return MAPPER.writeValueAsString(merged);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to merge JSON context", e);
        }
    }

    private static JsonNode deepMerge(JsonNode target, JsonNode source, boolean overwriteWithNull) {

        if (source == null || source.isNull()) {
            return target;
        }

        if (target.isObject() && source.isObject()) {

            ObjectNode targetObject = (ObjectNode) target;

            source.fields().forEachRemaining(entry -> {
                String fieldName = entry.getKey();
                JsonNode sourceValue = entry.getValue();
                JsonNode targetValue = targetObject.get(fieldName);

                if (targetValue != null && targetValue.isObject()
                        && sourceValue.isObject()) {

                    targetObject.set(
                            fieldName,
                            deepMerge(targetValue, sourceValue, overwriteWithNull)
                    );
                } else {
                    if (!overwriteWithNull && sourceValue != null && sourceValue.isNull() && targetObject.has(fieldName)) {
                        return;
                    }
                    targetObject.set(fieldName, sourceValue);
                }
            });

            return targetObject;
        }

        // for arrays or scalars, overwrite
        return source;
    }

    private static boolean isEmpty(String json) {
        return json == null || json.isBlank() || "{}".equals(json.trim());
    }

    /* ------------------------------------------------------------
     * SCHEMA COMPLETENESS CHECK
     * ------------------------------------------------------------ */
    public static boolean isSchemaComplete(String jsonSchema, String contextJson) {
        try {
            JsonNode schemaNode  = MAPPER.readTree(jsonSchema);
            JsonNode contextNode = MAPPER.readTree(contextJson);

            return isComplete(schemaNode, contextNode);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isComplete(JsonNode schema, JsonNode data) {

        // Type validation (basic)
        if (schema.has("type")) {
            String type = schema.get("type").asText();

            switch (type) {
                case "object" -> {
                    if (!data.isObject()) return false;
                }
                case "array" -> {
                    if (!data.isArray()) return false;

                    if (schema.has("minItems")) {
                        return data.size() >= schema.get("minItems").asInt();
                    }
                    return true;
                }
                case "string" -> {
                    return data.isTextual();
                }
            }
        }

        // Required fields
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode req : required) {
                String field = req.asText();

                if (!data.has(field) || data.get(field).isNull()) {
                    return false;
                }

                JsonNode properties = schema.get("properties");
                if (properties != null && properties.has(field)) {
                    if (!isComplete(properties.get(field), data.get(field))) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    public static boolean hasAnySchemaValue(String contextJson, String schemaJson) {
        try {
            JsonNode context = MAPPER.readTree(contextJson);
            JsonNode schema = MAPPER.readTree(schemaJson);
            JsonNode properties = schema.path("properties");

            if (!properties.isObject()) return false;

            Iterator<String> fieldNames = properties.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                JsonNode value = context.path(field);

                if (value.isMissingNode() || value.isNull()) continue;

                if (value.isTextual() && !value.asText().isBlank()) return true;
                if (value.isNumber()) return true;
                if (value.isBoolean()) return true;
                if (value.isArray() && !value.isEmpty()) return true;
                if (value.isObject() && !value.isEmpty()) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

}
