package com.nyx.media;

import com.nyx.ffmpeg.VideoTrickplayAssetOutput;
import com.nyx.ffmpeg.VideoTrickplayPlan;
import java.util.List;

public record VideoTrickplayResult(
    VideoTrickplayPlan plan,
    List<VideoTrickplayAssetOutput> assets
) {
    public VideoTrickplayPlan getPlan() {
        return plan;
    }

    public List<VideoTrickplayAssetOutput> getAssets() {
        return assets;
    }
}
