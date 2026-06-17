package com.nyx.ffmpeg;

import java.nio.file.Path;

public interface VideoPreviewGenerator {
    VideoPreviewPlan plan(Path sourcePath, VideoPreviewRequest request);

    byte[] generate(Path sourcePath, VideoPreviewPlan plan);

    default VideoPreviewOutput getPreview(Path sourcePath, VideoPreviewRequest request) {
        VideoPreviewPlan plan = plan(sourcePath, request);
        return new VideoPreviewOutput(generate(sourcePath, plan), plan);
    }
}
