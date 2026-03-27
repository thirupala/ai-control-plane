package com.decisionmesh.contracts.security.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA AttributeConverter for List<String> ↔ JSONB text.
 *
 * WHY THIS IS NEEDED:
 * Hibernate Reactive's ReactiveJsonJdbcType uses Vert.x JsonObject internally.
 * JsonObject only handles JSON objects {} — it cannot bind JSON arrays [].
 * Any List<T> field annotated with @JdbcTypeCode(SqlTypes.JSON) will throw:
 *   "Cannot deserialize value of type LinkedHashMap from Array value"
 * on INSERT because Vert.x tries new JsonObject("[]") which is illegal.
 *
 * This converter bypasses ReactiveJsonJdbcType entirely by mapping the List
 * to/from a plain String column — Hibernate treats it as TEXT/JSONB text
 * and never invokes the Vert.x JSON codec.
 *
 * USAGE — replace @JdbcTypeCode(SqlTypes.JSON) on List<String> fields with:
 *   @Convert(converter = StringListJsonConverter.class)
 *   @Column(name = "permissions", columnDefinition = "jsonb")
 *   public List<String> permissions = new ArrayList<>();
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_TYPE =
            new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize List<String> to JSON", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize JSON to List<String>: " + json, e);
        }
    }
}