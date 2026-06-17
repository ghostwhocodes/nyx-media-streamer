package com.nyx.di;

import com.nyx.ffmpeg.FfmpegVideoPreviewGenerator;
import com.nyx.ffmpeg.FfmpegVideoTrickplayGenerator;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.ProbeMetricsCollector;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.ffmpeg.SubtitleExtractor;
import com.nyx.ffmpeg.VideoPreviewGenerator;
import com.nyx.ffmpeg.VideoTrickplayGenerator;
import java.util.concurrent.Semaphore;

public final class FfmpegBindings {
    private final ProbeService probeService;
    private final MediaProber mediaProber;
    private final VideoPreviewGenerator videoPreviewGenerator;
    private final VideoTrickplayGenerator videoTrickplayGenerator;
    private final SubtitleExtractor subtitleExtractor;

    public FfmpegBindings(
        ProbeService probeService,
        MediaProber mediaProber,
        VideoPreviewGenerator videoPreviewGenerator,
        VideoTrickplayGenerator videoTrickplayGenerator,
        SubtitleExtractor subtitleExtractor
    ) {
        this.probeService = probeService;
        this.mediaProber = mediaProber;
        this.videoPreviewGenerator = videoPreviewGenerator;
        this.videoTrickplayGenerator = videoTrickplayGenerator;
        this.subtitleExtractor = subtitleExtractor;
    }

    public ProbeService getProbeService() {
        return probeService;
    }

    public MediaProber getMediaProber() {
        return mediaProber;
    }

    public VideoPreviewGenerator getVideoPreviewGenerator() {
        return videoPreviewGenerator;
    }

    public VideoTrickplayGenerator getVideoTrickplayGenerator() {
        return videoTrickplayGenerator;
    }

    public SubtitleExtractor getSubtitleExtractor() {
        return subtitleExtractor;
    }

    public static FfmpegBindings createFfmpegBindings(
        String ffmpegPath,
        String ffprobePath,
        Semaphore ffmpegSemaphore,
        ProbeMetricsCollector probeMetricsCollector
    ) {
        ProbeService probeService = new ProbeService(ffprobePath, probeMetricsCollector);
        return new FfmpegBindings(
            probeService,
            probeService,
            new FfmpegVideoPreviewGenerator(probeService, ffmpegPath, ffmpegSemaphore, 10, 320, 180),
            new FfmpegVideoTrickplayGenerator(
                probeService,
                ffmpegPath,
                ffmpegSemaphore,
                320,
                180,
                4,
                4,
                10,
                120,
                10_000L,
                5_000L
            ),
            new SubtitleExtractor(ffmpegPath)
        );
    }
}
