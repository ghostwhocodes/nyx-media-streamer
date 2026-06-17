package com.nyx.ffmpeg.model;

public record SegmentDuration(
    int initialSecs,
    int steadyStateSecs,
    int initialSegmentCount
) {
    public static final SegmentDuration ADAPTIVE = new SegmentDuration(2, 6, 4);
    public static final SegmentDuration FIXED_4S = new SegmentDuration(4, 4, 0);

    public int getInitialSecs() {
        return initialSecs;
    }

    public int getSteadyStateSecs() {
        return steadyStateSecs;
    }

    public int getInitialSegmentCount() {
        return initialSegmentCount;
    }
}
