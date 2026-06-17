package com.nyx.eforms.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record SearchQuery(
    String text,
    Map<String, JsonNode> filters,
    String formId,
    MediaType mediaType,
    String sortBy,
    Integer limit,
    Integer offset
) {
    public SearchQuery {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        limit = limit == null ? 50 : limit;
        offset = offset == null ? 0 : offset;
    }

    public SearchQuery() {
        this(null, Map.of(), null, null, null, 50, 0);
    }

    public SearchQuery(String text) {
        this(text, Map.of(), null, null, null, 50, 0);
    }
}
