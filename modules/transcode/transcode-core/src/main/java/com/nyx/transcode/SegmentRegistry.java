package com.nyx.transcode;

import java.util.List;

public interface SegmentRegistry {
    void register(String jobId, SegmentInfo segment);

    List<SegmentInfo> getSegments(String jobId);

    int count(String jobId);

    void clear(String jobId);

    void remove(String jobId);
}
