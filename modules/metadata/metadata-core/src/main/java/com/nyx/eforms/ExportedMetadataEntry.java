package com.nyx.eforms;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

public final class ExportedMetadataEntry {
    private final String formId;
    private final int formVersion;
    private final Map<String, JsonNode> values;
    private final String contentHash;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ExportedMetadataEntry(
        String formId,
        int formVersion,
        Map<String, JsonNode> values,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(formId, formVersion, values, null, createdAt, updatedAt);
    }

    @JsonCreator
    public ExportedMetadataEntry(
        @JsonProperty("formId") String formId,
        @JsonProperty("formVersion") int formVersion,
        @JsonProperty("values") Map<String, JsonNode> values,
        @JsonProperty("contentHash") String contentHash,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("updatedAt") Instant updatedAt
    ) {
        this.formId = formId;
        this.formVersion = formVersion;
        this.values = Map.copyOf(values);
        this.contentHash = contentHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getFormId() {
        return formId;
    }

    public int getFormVersion() {
        return formVersion;
    }

    public Map<String, JsonNode> getValues() {
        return values;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
