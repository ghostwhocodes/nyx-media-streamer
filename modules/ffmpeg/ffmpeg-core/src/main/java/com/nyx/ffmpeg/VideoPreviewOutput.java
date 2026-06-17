package com.nyx.ffmpeg;

public record VideoPreviewOutput(
    byte[] bytes,
    VideoPreviewPlan plan
) {
}
