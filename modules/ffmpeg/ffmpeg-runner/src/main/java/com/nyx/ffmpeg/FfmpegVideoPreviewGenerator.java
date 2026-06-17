package com.nyx.ffmpeg;

import static com.nyx.ffmpeg.MediaProberInterop.probeCachedOrThrow;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;

public final class FfmpegVideoPreviewGenerator implements VideoPreviewGenerator {
    private final MediaProber probeService;
    private final String ffmpegPath;
    private final Semaphore ffmpegSemaphore;
    private final int defaultSeekPercent;
    private final int defaultWidth;
    private final int defaultHeight;

    public FfmpegVideoPreviewGenerator(MediaProber probeService) {
        this(probeService, "ffmpeg", null, 10, 320, 180);
    }

    public FfmpegVideoPreviewGenerator(MediaProber probeService, String ffmpegPath) {
        this(probeService, ffmpegPath, null, 10, 320, 180);
    }

    public FfmpegVideoPreviewGenerator(MediaProber probeService, String ffmpegPath, Semaphore ffmpegSemaphore) {
        this(probeService, ffmpegPath, ffmpegSemaphore, 10, 320, 180);
    }

    public FfmpegVideoPreviewGenerator(
        MediaProber probeService,
        String ffmpegPath,
        Semaphore ffmpegSemaphore,
        int defaultSeekPercent,
        int defaultWidth,
        int defaultHeight
    ) {
        this.probeService = probeService;
        this.ffmpegPath = ffmpegPath;
        this.ffmpegSemaphore = ffmpegSemaphore;
        this.defaultSeekPercent = defaultSeekPercent;
        this.defaultWidth = defaultWidth;
        this.defaultHeight = defaultHeight;
    }

    @Override
    public VideoPreviewPlan plan(Path sourcePath, VideoPreviewRequest request) {
        requireRegularFile(sourcePath);
        validateRequest(request);

        final com.nyx.ffmpeg.model.ProbeResult probeResult;
        try {
            probeResult = probeCachedOrThrow(probeService, sourcePath);
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                "Failed to probe video preview source " + sourcePath + ": " + throwable.getMessage(),
                throwable
            );
        }
        var videoStream = probeResult.getStreams().getVideo().stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Source file is not a video: " + sourcePath));

        if (videoStream.getWidth() <= 0 || videoStream.getHeight() <= 0) {
            throw new IllegalArgumentException("Video preview source is missing dimensions: " + sourcePath);
        }

        long durationMillis = Math.max(0L, Math.round(probeResult.getDurationSecs() * 1000.0));
        long seekMillis = resolveSeekMillis(request, durationMillis);
        Dimensions outputDimensions = resolveOutputDimensions(
            videoStream.getWidth(),
            videoStream.getHeight(),
            request.getWidth(),
            request.getHeight()
        );

        return new VideoPreviewPlan(
            videoStream.getWidth(),
            videoStream.getHeight(),
            seekMillis,
            outputDimensions.width(),
            outputDimensions.height(),
            "jpeg",
            "jpg",
            "image/jpeg"
        );
    }

    @Override
    public byte[] generate(Path sourcePath, VideoPreviewPlan plan) {
        return withSemaphore(() -> {
            try {
                Path tempFile = Files.createTempFile("nyx-video-preview-", "." + plan.getOutputExtension());
                try {
                    Process process = new ProcessBuilder(buildCommand(sourcePath, tempFile, plan))
                        .redirectErrorStream(true)
                        .start();

                    String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
                    int exitCode = waitFor(process);
                    if (exitCode != 0) {
                        throw new IOException("Video preview generation failed for " + sourcePath + ": " + output);
                    }

                    if (!Files.exists(tempFile)) {
                        throw new IOException("Video preview frame was not created: " + tempFile);
                    }

                    return Files.readAllBytes(tempFile);
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            } catch (IOException exception) {
                return UncheckedThrow.sneakyThrow(exception);
            }
        });
    }

    private List<String> buildCommand(Path sourcePath, Path outputPath, VideoPreviewPlan plan) {
        String seekSeconds = String.format(Locale.ROOT, "%.3f", plan.getSeekMillis() / 1000.0);
        return List.of(
            ffmpegPath,
            "-ss", seekSeconds,
            "-i", sourcePath.toString(),
            "-vf", "scale=" + plan.getOutputWidth() + ":" + plan.getOutputHeight(),
            "-frames:v", "1",
            "-q:v", "2",
            "-y",
            outputPath.toString()
        );
    }

    private void validateRequest(VideoPreviewRequest request) {
        Long positionMillis = request.getPositionMillis();
        Integer percent = request.getPercent();
        Integer width = request.getWidth();
        Integer height = request.getHeight();

        if (positionMillis != null && positionMillis < 0L) {
            throw new IllegalArgumentException("positionMillis must be non-negative");
        }
        if (percent != null && (percent < 0 || percent > 100)) {
            throw new IllegalArgumentException("percent must be between 0 and 100");
        }
        if (width != null && width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height != null && height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        if (positionMillis != null && percent != null) {
            throw new IllegalArgumentException("Specify either positionMillis or percent, not both");
        }
    }

    private long resolveSeekMillis(VideoPreviewRequest request, long durationMillis) {
        Long positionMillis = request.getPositionMillis();
        Integer percent = request.getPercent();
        long requested = positionMillis != null
            ? positionMillis
            : scaleByPercent(durationMillis, percent != null ? percent : defaultSeekPercent);

        if (durationMillis <= 0L) {
            return requested;
        }

        long maxSeek = Math.max(0L, durationMillis - 1L);
        return Math.max(0L, Math.min(requested, maxSeek));
    }

    private long scaleByPercent(long durationMillis, int percent) {
        if (durationMillis <= 0L) {
            return 0L;
        }
        return Math.round((durationMillis * percent) / 100.0);
    }

    private Dimensions resolveOutputDimensions(
        int sourceWidth,
        int sourceHeight,
        Integer requestWidth,
        Integer requestHeight
    ) {
        if (requestWidth != null && requestHeight != null) {
            return scaleToBox(sourceWidth, sourceHeight, requestWidth, requestHeight);
        }
        if (requestWidth != null) {
            return scaleToWidth(sourceWidth, sourceHeight, requestWidth);
        }
        if (requestHeight != null) {
            return scaleToHeight(sourceWidth, sourceHeight, requestHeight);
        }
        return scaleToBox(sourceWidth, sourceHeight, defaultWidth, defaultHeight);
    }

    private Dimensions scaleToBox(int sourceWidth, int sourceHeight, int boxWidth, int boxHeight) {
        double scale = Math.min(
            1.0,
            Math.min(
                boxWidth / (double) sourceWidth,
                boxHeight / (double) sourceHeight
            )
        );
        return scaleDimensions(sourceWidth, sourceHeight, scale);
    }

    private Dimensions scaleToWidth(int sourceWidth, int sourceHeight, int width) {
        double scale = Math.min(1.0, width / (double) sourceWidth);
        return scaleDimensions(sourceWidth, sourceHeight, scale);
    }

    private Dimensions scaleToHeight(int sourceWidth, int sourceHeight, int height) {
        double scale = Math.min(1.0, height / (double) sourceHeight);
        return scaleDimensions(sourceWidth, sourceHeight, scale);
    }

    private Dimensions scaleDimensions(int sourceWidth, int sourceHeight, double scale) {
        int width = Math.max(1, (int) Math.round(sourceWidth * scale));
        int height = Math.max(1, (int) Math.round(sourceHeight * scale));
        return new Dimensions(width, height);
    }

    private static void requireRegularFile(Path sourcePath) {
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("Source file does not exist: " + sourcePath);
        }
    }

    private static int waitFor(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return UncheckedThrow.sneakyThrow(exception);
        }
    }

    private <T> T withSemaphore(ThrowingSupplier<T> supplier) {
        if (ffmpegSemaphore == null) {
            return supplier.get();
        }

        try {
            ffmpegSemaphore.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return UncheckedThrow.sneakyThrow(exception);
        }

        try {
            return supplier.get();
        } finally {
            ffmpegSemaphore.release();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }

    private record Dimensions(int width, int height) {
    }
}
