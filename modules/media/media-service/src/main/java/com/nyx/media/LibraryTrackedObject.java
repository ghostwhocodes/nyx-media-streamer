package com.nyx.media;

import com.nyx.media.contracts.MediaKind;
import java.util.Objects;

public record LibraryTrackedObject(
    String libraryEntryId,
    String libraryId,
    String objectId,
    String sourceRootId,
    MediaKind mediaKind,
    String primaryPath,
    String pathKey,
    LibraryTrackedObjectStatus status,
    String firstScannedAt,
    String lastScannedAt,
    String missingAt,
    String lastScanRunId
) {
    public LibraryTrackedObject {
        libraryEntryId = Objects.requireNonNull(libraryEntryId, "libraryEntryId");
        libraryId = Objects.requireNonNull(libraryId, "libraryId");
        objectId = Objects.requireNonNull(objectId, "objectId");
        sourceRootId = Objects.requireNonNull(sourceRootId, "sourceRootId");
        mediaKind = Objects.requireNonNull(mediaKind, "mediaKind");
        primaryPath = Objects.requireNonNull(primaryPath, "primaryPath");
        pathKey = Objects.requireNonNull(pathKey, "pathKey");
        status = Objects.requireNonNull(status, "status");
        firstScannedAt = Objects.requireNonNull(firstScannedAt, "firstScannedAt");
        lastScannedAt = Objects.requireNonNull(lastScannedAt, "lastScannedAt");
    }

    public String getLibraryEntryId() {
        return libraryEntryId;
    }

    public String getLibraryId() {
        return libraryId;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getSourceRootId() {
        return sourceRootId;
    }

    public MediaKind getMediaKind() {
        return mediaKind;
    }

    public String getPrimaryPath() {
        return primaryPath;
    }

    public String getPathKey() {
        return pathKey;
    }

    public LibraryTrackedObjectStatus getStatus() {
        return status;
    }

    public String getFirstScannedAt() {
        return firstScannedAt;
    }

    public String getLastScannedAt() {
        return lastScannedAt;
    }

    public String getMissingAt() {
        return missingAt;
    }

    public String getLastScanRunId() {
        return lastScanRunId;
    }
}
