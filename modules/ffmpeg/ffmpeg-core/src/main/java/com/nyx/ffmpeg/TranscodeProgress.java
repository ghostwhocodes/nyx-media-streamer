package com.nyx.ffmpeg;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TranscodeProgress(
    long frame,
    double fps,
    long totalSize,
    long outTimeUs,
    double speed,
    double bitrate,
    double progressPercent
) {
    public TranscodeProgress() {
        this(0L, 0.0, 0L, 0L, 0.0, 0.0, 0.0);
    }
}
