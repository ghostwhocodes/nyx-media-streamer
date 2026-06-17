package com.nyx.eforms.model;

import java.time.Instant;
import java.util.List;

public record FormVersion(
    int version,
    List<FieldDefinition> fields,
    Instant createdAt
) {
    public FormVersion {
        fields = List.copyOf(fields);
    }
}
