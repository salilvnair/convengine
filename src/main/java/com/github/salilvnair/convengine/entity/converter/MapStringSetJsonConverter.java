package com.github.salilvnair.convengine.entity.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;

@Slf4j
@Converter
public class MapStringSetJsonConverter implements AttributeConverter<Map<String, Set<String>>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Set<String>>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Set<String>> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            log.warn("Failed to serialize allowed identifiers map to JSON", e);
            return null;
        }
    }

    @Override
    public Map<String, Set<String>> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            log.warn("Failed to deserialize allowed identifiers JSON", e);
            return null;
        }
    }
}
