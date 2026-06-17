package com.nyx.eforms.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record FieldDefinition(
    String name,
    FieldType type,
    boolean required,
    List<String> options,
    JsonNode defaultValue
) {
    public FieldDefinition {
        options = options == null ? null : List.copyOf(options);
    }

    public FieldDefinition(String name, FieldType type) {
        this(name, type, false, null, null);
    }

    public FieldDefinition(String name, FieldType type, boolean required) {
        this(name, type, required, null, null);
    }

    public FieldDefinition(String name, FieldType type, boolean required, List<String> options) {
        this(name, type, required, options, null);
    }
}
