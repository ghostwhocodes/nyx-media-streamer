package com.nyx.ffmpeg;

import java.nio.file.Path;
import java.util.List;

public interface VideoTrickplayGenerator {
    VideoTrickplayPlan plan(Path sourcePath, VideoTrickplayRequest request);

    byte[] generate(Path sourcePath, VideoTrickplayAssetPlan plan);

    default List<VideoTrickplayAssetOutput> generate(Path sourcePath, VideoTrickplayPlan plan) {
        return plan.assets().stream()
            .map(assetPlan -> new VideoTrickplayAssetOutput(generate(sourcePath, assetPlan), assetPlan))
            .toList();
    }
}
