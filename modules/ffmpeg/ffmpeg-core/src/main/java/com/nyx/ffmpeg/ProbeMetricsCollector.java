package com.nyx.ffmpeg;

public interface ProbeMetricsCollector {
    void recordProbeCacheHit();

    void recordProbeCacheMiss();

    void recordProbeDuration(long nanos);
}
