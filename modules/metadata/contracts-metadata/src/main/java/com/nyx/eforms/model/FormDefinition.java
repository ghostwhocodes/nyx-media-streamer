package com.nyx.eforms.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record FormDefinition(
    String id,
    String name,
    int currentVersion,
    Set<MediaType> mediaTypes,
    List<FormVersion> versions,
    Instant createdAt,
    Instant updatedAt
) {
    public FormDefinition {
        mediaTypes = Set.copyOf(mediaTypes);
        versions = versions == null ? List.of() : List.copyOf(versions);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }

    public FormDefinition(String id, String name, int currentVersion, Set<MediaType> mediaTypes) {
        this(id, name, currentVersion, mediaTypes, List.of(), Instant.EPOCH, Instant.EPOCH);
    }

    public FormDefinition(
        String id,
        String name,
        int currentVersion,
        Set<MediaType> mediaTypes,
        List<FormVersion> versions
    ) {
        this(id, name, currentVersion, mediaTypes, versions, Instant.EPOCH, Instant.EPOCH);
    }
}
