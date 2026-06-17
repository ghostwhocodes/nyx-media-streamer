package com.nyx.transcode;

import java.nio.file.Path;
import java.util.Objects;

public record TranscodeEngineConfig(
    FfmpegRuntimeConfig ffmpeg,
    RuntimeConfig transcode,
    Path databaseDir
) {
    public TranscodeEngineConfig {
        ffmpeg = Objects.requireNonNull(ffmpeg, "ffmpeg");
        transcode = Objects.requireNonNull(transcode, "transcode");
        databaseDir = Objects.requireNonNull(databaseDir, "databaseDir");
    }

    public FfmpegRuntimeConfig getFfmpeg() {
        return ffmpeg;
    }

    public RuntimeConfig getTranscode() {
        return transcode;
    }

    public Path getDatabaseDir() {
        return databaseDir;
    }

    public record FfmpegRuntimeConfig(
        String path,
        int maxConcurrentJobs,
        int maxQueuedJobs,
        String watchStrategy,
        long segmentWatchPollIntervalMs
    ) {
        public FfmpegRuntimeConfig {
            path = Objects.requireNonNull(path, "path");
            watchStrategy = Objects.requireNonNull(watchStrategy, "watchStrategy");
        }

        public String getPath() {
            return path;
        }

        public int getMaxConcurrentJobs() {
            return maxConcurrentJobs;
        }

        public int getMaxQueuedJobs() {
            return maxQueuedJobs;
        }

        public String getWatchStrategy() {
            return watchStrategy;
        }

        public long getSegmentWatchPollIntervalMs() {
            return segmentWatchPollIntervalMs;
        }
    }

    public record RuntimeConfig(
        int circuitBreakerThreshold,
        int segmentDurationSteadyStateSecs,
        long minFreeDiskBytes,
        int maxRetries,
        long retryBackoffMs,
        int segmentCacheGracePeriodMinutes
    ) {
        public int getCircuitBreakerThreshold() {
            return circuitBreakerThreshold;
        }

        public int getSegmentDurationSteadyStateSecs() {
            return segmentDurationSteadyStateSecs;
        }

        public long getMinFreeDiskBytes() {
            return minFreeDiskBytes;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public long getRetryBackoffMs() {
            return retryBackoffMs;
        }

        public int getSegmentCacheGracePeriodMinutes() {
            return segmentCacheGracePeriodMinutes;
        }
    }
}
