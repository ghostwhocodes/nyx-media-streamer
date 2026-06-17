package com.nyx.media;

import java.util.List;

public record LibraryExtensionRunSummary(
    List<String> metadataProvidersInvoked,
    List<String> artworkProvidersInvoked,
    List<String> collectionBuildersInvoked,
    List<String> scanHooksInvoked,
    List<String> scheduledJobsInvoked
) {
    public LibraryExtensionRunSummary {
        metadataProvidersInvoked = metadataProvidersInvoked == null ? List.of() : List.copyOf(metadataProvidersInvoked);
        artworkProvidersInvoked = artworkProvidersInvoked == null ? List.of() : List.copyOf(artworkProvidersInvoked);
        collectionBuildersInvoked = collectionBuildersInvoked == null ? List.of() : List.copyOf(collectionBuildersInvoked);
        scanHooksInvoked = scanHooksInvoked == null ? List.of() : List.copyOf(scanHooksInvoked);
        scheduledJobsInvoked = scheduledJobsInvoked == null ? List.of() : List.copyOf(scheduledJobsInvoked);
    }

    public LibraryExtensionRunSummary() {
        this(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public List<String> getMetadataProvidersInvoked() {
        return metadataProvidersInvoked;
    }

    public List<String> getArtworkProvidersInvoked() {
        return artworkProvidersInvoked;
    }

    public List<String> getCollectionBuildersInvoked() {
        return collectionBuildersInvoked;
    }

    public List<String> getScanHooksInvoked() {
        return scanHooksInvoked;
    }

    public List<String> getScheduledJobsInvoked() {
        return scheduledJobsInvoked;
    }
}
