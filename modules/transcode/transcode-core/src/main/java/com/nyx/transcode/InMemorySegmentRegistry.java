package com.nyx.transcode;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemorySegmentRegistry implements SegmentRegistry {
    private final ConcurrentHashMap<String, List<SegmentInfo>> segments = new ConcurrentHashMap<>();

    @Override
    public void register(String jobId, SegmentInfo segment) {
        segments.computeIfAbsent(jobId, ignored -> new CopyOnWriteArrayList<>()).add(segment);
    }

    @Override
    public List<SegmentInfo> getSegments(String jobId) {
        return segments.getOrDefault(jobId, List.of());
    }

    @Override
    public int count(String jobId) {
        return segments.getOrDefault(jobId, List.of()).size();
    }

    @Override
    public void clear(String jobId) {
        List<SegmentInfo> registeredSegments = segments.get(jobId);
        if (registeredSegments != null) {
            registeredSegments.clear();
        }
    }

    @Override
    public void remove(String jobId) {
        segments.remove(jobId);
    }
}
