package com.nyx.eforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.nyx.eforms.model.FieldDefinition;
import com.nyx.eforms.model.FieldType;
import com.nyx.eforms.model.FormDefinition;
import com.nyx.eforms.model.FormVersion;
import com.nyx.eforms.model.MediaType;
import com.nyx.eforms.model.SearchQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MetadataContractFactories {
    private MetadataContractFactories() {
    }

    static FieldDefinition fieldDefinition(String name, FieldType type) {
        return fieldDefinition(name, type, false, null, null);
    }

    static FieldDefinition fieldDefinition(String name, FieldType type, boolean required) {
        return fieldDefinition(name, type, required, null, null);
    }

    static FieldDefinition fieldDefinition(
        String name,
        FieldType type,
        boolean required,
        List<String> options
    ) {
        return fieldDefinition(name, type, required, options, null);
    }

    static FieldDefinition fieldDefinition(
        String name,
        FieldType type,
        boolean required,
        List<String> options,
        JsonNode defaultValue
    ) {
        return new FieldDefinition(name, type, required, options, defaultValue);
    }

    static FormVersion formVersion(int version, List<FieldDefinition> fields, Instant createdAt) {
        return new FormVersion(version, fields, createdAt);
    }

    static FormDefinition formDefinition(
        String id,
        String name,
        int currentVersion,
        Set<MediaType> mediaTypes,
        List<FormVersion> versions,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new FormDefinition(id, name, currentVersion, mediaTypes, versions, createdAt, updatedAt);
    }

    static SearchQuery searchQuery() {
        return new SearchQuery();
    }

    static SearchQuery searchQuery(String text) {
        return new SearchQuery(text);
    }

    static SearchQuery searchQuery(
        String text,
        Map<String, JsonNode> filters,
        String formId,
        MediaType mediaType,
        String sortBy,
        Integer limit,
        Integer offset
    ) {
        return new SearchQuery(text, filters, formId, mediaType, sortBy, limit, offset);
    }
}
