package com.nyx.eforms.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

public record MediaMetadata(
    String id,
    String mediaPath,
    String contentHash,
    String formId,
    int formVersion,
    Map<String, JsonNode> values,
    Instant createdAt,
    Instant updatedAt
) {
    public MediaMetadata {
        values = Map.copyOf(values);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }

    public MediaMetadata(
        String id,
        String mediaPath,
        String formId,
        int formVersion,
        Map<String, JsonNode> values
    ) {
        this(id, mediaPath, null, formId, formVersion, values, Instant.EPOCH, Instant.EPOCH);
    }

    public MediaMetadata(
        String id,
        String mediaPath,
        String contentHash,
        String formId,
        int formVersion,
        Map<String, JsonNode> values
    ) {
        this(id, mediaPath, contentHash, formId, formVersion, values, Instant.EPOCH, Instant.EPOCH);
    }
}
