package com.github.salilvnair.convengine.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AuditPayloadMapper {
    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> payloadAsMap(String payloadJson) {
        try {
            if (payloadJson == null || payloadJson.isBlank()) {
                return new LinkedHashMap<>();
            }
            JsonNode root = mapper.readTree(payloadJson);
            if (!root.isObject()) {
                return Map.of("value", root.toString());
            }
            return mapper.convertValue(root, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("raw_payload", payloadJson == null ? "" : payloadJson);
        }
    }
}
