package com.github.salilvnair.convengine.entity.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Converter
public class OffsetDateTimeStringConverter implements AttributeConverter<OffsetDateTime, String> {

    private static final DateTimeFormatter WRITE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX");
    private static final DateTimeFormatter READ_WITH_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX");
    private static final DateTimeFormatter READ_WITH_ZONE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS Z");
    private static final DateTimeFormatter READ_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter READ_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String convertToDatabaseColumn(OffsetDateTime attribute) {
        if (attribute == null) {
            return null;
        }
        return WRITE_FMT.format(attribute);
    }

    @Override
    public OffsetDateTime convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        String value = dbData.trim();

        // SQLite may carry epoch millis text in legacy rows (e.g. "1771459629905").
        if (value.matches("^\\d{10,}$")) {
            long epoch = Long.parseLong(value);
            return Instant.ofEpochMilli(epoch).atOffset(ZoneOffset.UTC);
        }

        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(value, READ_WITH_OFFSET);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(value, READ_WITH_ZONE);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value, READ_MILLIS).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value, READ_SECONDS).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        throw new IllegalArgumentException("Unsupported date-time value: " + dbData);
    }
}
