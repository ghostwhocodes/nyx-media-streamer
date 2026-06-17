package com.nyx.media;

import java.util.List;

public record RegisteredLibraryExtensions(
    List<String> metadataProviders,
    List<String> artworkProviders,
    List<String> collectionBuilders,
    List<String> scanHooks,
    List<String> scheduledJobs
) {
    public RegisteredLibraryExtensions {
        metadataProviders = metadataProviders == null ? List.of() : List.copyOf(metadataProviders);
        artworkProviders = artworkProviders == null ? List.of() : List.copyOf(artworkProviders);
        collectionBuilders = collectionBuilders == null ? List.of() : List.copyOf(collectionBuilders);
        scanHooks = scanHooks == null ? List.of() : List.copyOf(scanHooks);
        scheduledJobs = scheduledJobs == null ? List.of() : List.copyOf(scheduledJobs);
    }

    public RegisteredLibraryExtensions() {
        this(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public List<String> getMetadataProviders() {
        return metadataProviders;
    }

    public List<String> getArtworkProviders() {
        return artworkProviders;
    }

    public List<String> getCollectionBuilders() {
        return collectionBuilders;
    }

    public List<String> getScanHooks() {
        return scanHooks;
    }

    public List<String> getScheduledJobs() {
        return scheduledJobs;
    }
}
