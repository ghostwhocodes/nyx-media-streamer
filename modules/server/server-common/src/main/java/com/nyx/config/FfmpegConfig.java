package com.nyx.config;

import java.util.Map;
import java.util.Objects;

public record FfmpegConfig(
    String path,
    String ffprobePath,
    String minVersion,
    int maxConcurrentJobs,
    int maxConcurrentMediaProcesses,
    int maxQueuedJobs,
    Map<String, String> qualityPresets,
    String watchStrategy,
    long segmentWatchPollIntervalMs
) {
    public static final Map<String, String> DEFAULT_QUALITY_PRESETS = Map.of(
        "low", "h264_fast",
        "medium", "h264_balanced",
        "high", "h265_quality"
    );

    public FfmpegConfig {
        path = Objects.requireNonNull(path, "path");
        ffprobePath = Objects.requireNonNull(ffprobePath, "ffprobePath");
        minVersion = Objects.requireNonNull(minVersion, "minVersion");
        qualityPresets = qualityPresets == null ? DEFAULT_QUALITY_PRESETS : Map.copyOf(qualityPresets);
        watchStrategy = watchStrategy == null ? "polling" : watchStrategy;
    }

    public FfmpegConfig(String path, String ffprobePath, String minVersion, int maxConcurrentJobs) {
        this(path, ffprobePath, minVersion, maxConcurrentJobs, 4, 8, DEFAULT_QUALITY_PRESETS, "polling", 500L);
    }

    public String getPath() {
        return path;
    }

    public String getFfprobePath() {
        return ffprobePath;
    }

    public String getMinVersion() {
        return minVersion;
    }

    public int getMaxConcurrentJobs() {
        return maxConcurrentJobs;
    }

    public int getMaxConcurrentMediaProcesses() {
        return maxConcurrentMediaProcesses;
    }

    public int getMaxQueuedJobs() {
        return maxQueuedJobs;
    }

    public Map<String, String> getQualityPresets() {
        return qualityPresets;
    }

    public String getWatchStrategy() {
        return watchStrategy;
    }

    public long getSegmentWatchPollIntervalMs() {
        return segmentWatchPollIntervalMs;
    }
}
