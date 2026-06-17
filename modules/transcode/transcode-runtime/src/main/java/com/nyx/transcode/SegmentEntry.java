package com.nyx.transcode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public final class SegmentEntry {
    private final Path path;
    private final String jobId;
    private final AtomicInteger refCount;
    private volatile Instant gracePeriodStart;

    public SegmentEntry(Path path, String jobId) {
        this(path, jobId, new AtomicInteger(0), null);
    }

    public SegmentEntry(Path path, String jobId, AtomicInteger refCount, Instant gracePeriodStart) {
        this.path = Objects.requireNonNull(path, "path");
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.refCount = Objects.requireNonNull(refCount, "refCount");
        this.gracePeriodStart = gracePeriodStart;
    }

    public Path getPath() {
        return path;
    }

    public String getJobId() {
        return jobId;
    }

    public AtomicInteger getRefCount() {
        return refCount;
    }

    public Instant getGracePeriodStart() {
        return gracePeriodStart;
    }

    void setGracePeriodStart(Instant gracePeriodStart) {
        this.gracePeriodStart = gracePeriodStart;
    }
}
