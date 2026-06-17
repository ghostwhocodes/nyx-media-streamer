package com.nyx.media;

import java.util.Objects;

public record LibraryScanRun(
    String scanRunId,
    String libraryId,
    LibraryScanMode mode,
    LibraryScanRunStatus status,
    String createdAt,
    String startedAt,
    String completedAt,
    String errorMessage,
    int filesScanned,
    int importedCount,
    int refreshedCount,
    int missingCount
) {
    public LibraryScanRun(String scanRunId, String libraryId, LibraryScanMode mode, LibraryScanRunStatus status, String createdAt) {
        this(scanRunId, libraryId, mode, status, createdAt, null, null, null, 0, 0, 0, 0);
    }

    public LibraryScanRun {
        scanRunId = Objects.requireNonNull(scanRunId, "scanRunId");
        libraryId = Objects.requireNonNull(libraryId, "libraryId");
        mode = Objects.requireNonNull(mode, "mode");
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public String getScanRunId() {
        return scanRunId;
    }

    public String getLibraryId() {
        return libraryId;
    }

    public LibraryScanMode getMode() {
        return mode;
    }

    public LibraryScanRunStatus getStatus() {
        return status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public String getCompletedAt() {
        return completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getFilesScanned() {
        return filesScanned;
    }

    public int getImportedCount() {
        return importedCount;
    }

    public int getRefreshedCount() {
        return refreshedCount;
    }

    public int getMissingCount() {
        return missingCount;
    }
}
