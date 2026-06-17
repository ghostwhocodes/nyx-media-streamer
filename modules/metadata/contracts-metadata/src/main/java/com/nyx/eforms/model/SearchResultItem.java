package com.nyx.eforms.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record SearchResultItem(
    String mediaPath,
    MediaType mediaType,
    String formId,
    int formVersion,
    Map<String, JsonNode> metadata,
    Double relevance
) {
    public SearchResultItem {
        metadata = Map.copyOf(metadata);
    }

    public SearchResultItem(
        String mediaPath,
        String formId,
        int formVersion,
        Map<String, JsonNode> metadata
    ) {
        this(mediaPath, null, formId, formVersion, metadata, null);
    }
}
