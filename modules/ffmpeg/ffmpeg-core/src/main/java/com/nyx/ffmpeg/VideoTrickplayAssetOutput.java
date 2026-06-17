package com.nyx.ffmpeg;

public record VideoTrickplayAssetOutput(
    byte[] bytes,
    VideoTrickplayAssetPlan plan
) {
}
