package com.nyx.transcode;

import com.nyx.config.FfmpegConfig;
import com.nyx.config.ServerConfig;
import com.nyx.config.TranscodeConfig;
import java.util.Objects;

public final class TranscodeEngineConfigMapper {
    private TranscodeEngineConfigMapper() {
    }

    public static TranscodeEngineConfig toTranscodeEngineConfig(ServerConfig config) {
        Objects.requireNonNull(config, "config");
        FfmpegConfig ffmpeg = config.getFfmpeg();
        TranscodeConfig transcode = config.getTranscode();
        return new TranscodeEngineConfig(
            new TranscodeEngineConfig.FfmpegRuntimeConfig(
                ffmpeg.getPath(),
                ffmpeg.getMaxConcurrentJobs(),
                ffmpeg.getMaxQueuedJobs(),
                ffmpeg.getWatchStrategy(),
                ffmpeg.getSegmentWatchPollIntervalMs()
            ),
            new TranscodeEngineConfig.RuntimeConfig(
                transcode.getCircuitBreakerThreshold(),
                transcode.getSegmentDurationSteadyStateSecs(),
                transcode.getMinFreeDiskBytes(),
                transcode.getMaxRetries(),
                transcode.getRetryBackoffMs(),
                transcode.getSegmentCacheGracePeriodMinutes()
            ),
            config.getDatabase().getDir()
        );
    }
}
