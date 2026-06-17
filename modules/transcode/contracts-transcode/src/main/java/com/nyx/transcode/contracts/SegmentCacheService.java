package com.nyx.transcode.contracts;

import java.nio.file.Path;

public interface SegmentCacheService {
    void register(Path segmentPath, String jobId);

    Path acquire(Path segmentPath);

    void release(Path segmentPath);

    void startGracePeriod(String jobId);

    void purgeAll();

    int entryCount();
}
