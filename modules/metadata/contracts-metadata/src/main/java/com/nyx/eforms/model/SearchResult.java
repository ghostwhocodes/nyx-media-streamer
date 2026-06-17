package com.nyx.eforms.model;

import java.util.List;

public record SearchResult(
    List<SearchResultItem> results,
    int total,
    int limit,
    int offset
) {
    public SearchResult {
        results = List.copyOf(results);
    }
}
