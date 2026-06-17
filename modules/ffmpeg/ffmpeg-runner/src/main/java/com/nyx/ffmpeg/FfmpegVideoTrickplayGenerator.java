package com.nyx.ffmpeg;

import static com.nyx.ffmpeg.MediaProberInterop.probeCachedOrThrow;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;

public final class FfmpegVideoTrickplayGenerator implements VideoTrickplayGenerator {
    private final MediaProber probeService;
    private final String ffmpegPath;
    private final Semaphore ffmpegSemaphore;
    private final int defaultThumbnailWidth;
    private final int defaultThumbnailHeight;
    private final int defaultSheetColumns;
    private final int defaultSheetRows;
    private final int defaultStripColumns;
    private final int defaultTimelineFrames;
    private final long minIntervalMillis;
    private final long intervalBucketMillis;

    public FfmpegVideoTrickplayGenerator(MediaProber probeService) {
        this(probeService, "ffmpeg", null, 320, 180, 4, 4, 10, 120, 10_000L, 5_000L);
    }

    public FfmpegVideoTrickplayGenerator(MediaProber probeService, String ffmpegPath) {
        this(probeService, ffmpegPath, null, 320, 180, 4, 4, 10, 120, 10_000L, 5_000L);
    }

    public FfmpegVideoTrickplayGenerator(MediaProber probeService, String ffmpegPath, Semaphore ffmpegSemaphore) {
        this(probeService, ffmpegPath, ffmpegSemaphore, 320, 180, 4, 4, 10, 120, 10_000L, 5_000L);
    }

    public FfmpegVideoTrickplayGenerator(
        MediaProber probeService,
        String ffmpegPath,
        Semaphore ffmpegSemaphore,
        int defaultThumbnailWidth,
        int defaultThumbnailHeight,
        int defaultSheetColumns,
        int defaultSheetRows,
        int defaultStripColumns,
        int defaultTimelineFrames,
        long minIntervalMillis,
        long intervalBucketMillis
    ) {
        this.probeService = probeService;
        this.ffmpegPath = ffmpegPath;
        this.ffmpegSemaphore = ffmpegSemaphore;
        this.defaultThumbnailWidth = defaultThumbnailWidth;
        this.defaultThumbnailHeight = defaultThumbnailHeight;
        this.defaultSheetColumns = defaultSheetColumns;
        this.defaultSheetRows = defaultSheetRows;
        this.defaultStripColumns = defaultStripColumns;
        this.defaultTimelineFrames = defaultTimelineFrames;
        this.minIntervalMillis = minIntervalMillis;
        this.intervalBucketMillis = intervalBucketMillis;
    }

    @Override
    public VideoTrickplayPlan plan(Path sourcePath, VideoTrickplayRequest request) {
        requireRegularFile(sourcePath);
        validateRequest(request);

        final com.nyx.ffmpeg.model.ProbeResult probeResult;
        try {
            probeResult = probeCachedOrThrow(probeService, sourcePath);
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                "Failed to probe trickplay source " + sourcePath + ": " + throwable.getMessage(),
                throwable
            );
        }
        var videoStream = probeResult.getStreams().getVideo().stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Source file is not a video: " + sourcePath));

        if (videoStream.getWidth() <= 0 || videoStream.getHeight() <= 0) {
            throw new IllegalArgumentException("Trickplay source is missing dimensions: " + sourcePath);
        }

        long durationMillis = Math.max(0L, Math.round(probeResult.getDurationSecs() * 1000.0));
        long intervalMillis = resolveIntervalMillis(request.getIntervalMillis(), durationMillis);
        Dimensions thumbnailDimensions = resolveThumbnailDimensions(
            videoStream.getWidth(),
            videoStream.getHeight(),
            request.getThumbnailWidth(),
            request.getThumbnailHeight()
        );
        List<Long> capturePositions = resolveCapturePositions(durationMillis, intervalMillis);
        List<TrickplayAssetKind> requestedKinds = new ArrayList<>(request.getAssetKinds());
        requestedKinds.sort(Comparator.comparingInt(Enum::ordinal));
        int resolvedSheetColumns = request.getTileColumns() != null ? request.getTileColumns() : defaultSheetColumns;
        int resolvedSheetRows = request.getTileRows() != null ? request.getTileRows() : defaultSheetRows;
        int resolvedStripColumns = request.getTileColumns() != null ? request.getTileColumns() : defaultStripColumns;

        int nextAssetIndex = 0;
        List<VideoTrickplayAssetPlan> assets = new ArrayList<>();
        for (TrickplayAssetKind kind : requestedKinds) {
            List<VideoTrickplayAssetPlan> assetPlans;
            if (kind == TrickplayAssetKind.STORYBOARD_SHEET) {
                assetPlans = buildAssetPlans(
                    kind,
                    capturePositions,
                    intervalMillis,
                    resolvedSheetColumns,
                    resolvedSheetRows,
                    thumbnailDimensions.width(),
                    thumbnailDimensions.height(),
                    nextAssetIndex
                );
            } else {
                assetPlans = buildAssetPlans(
                    kind,
                    capturePositions,
                    intervalMillis,
                    resolvedStripColumns,
                    1,
                    thumbnailDimensions.width(),
                    thumbnailDimensions.height(),
                    nextAssetIndex
                );
            }
            assets.addAll(assetPlans);
            nextAssetIndex += assetPlans.size();
        }

        return new VideoTrickplayPlan(
            videoStream.getWidth(),
            videoStream.getHeight(),
            durationMillis,
            intervalMillis,
            thumbnailDimensions.width(),
            thumbnailDimensions.height(),
            resolvedSheetColumns,
            resolvedSheetRows,
            assets,
            buildTimelineEntries(assets)
        );
    }

    @Override
    public byte[] generate(Path sourcePath, VideoTrickplayAssetPlan plan) {
        return withSemaphore(() -> {
            try {
                Path tempFile = Files.createTempFile("nyx-video-trickplay-", "." + plan.getOutputExtension());
                try {
                    Process process = new ProcessBuilder(buildCommand(sourcePath, tempFile, plan))
                        .redirectErrorStream(true)
                        .start();

                    String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
                    int exitCode = waitFor(process);
                    if (exitCode != 0) {
                        throw new IOException("Video trickplay generation failed for " + sourcePath + ": " + output);
                    }

                    if (!Files.exists(tempFile)) {
                        throw new IOException("Video trickplay image was not created: " + tempFile);
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

    private void validateRequest(VideoTrickplayRequest request) {
        if (request.getAssetKinds().isEmpty()) {
            throw new IllegalArgumentException("assetKinds must not be empty");
        }
        if (request.getIntervalMillis() != null && request.getIntervalMillis() < 1_000L) {
            throw new IllegalArgumentException("intervalMillis must be at least 1000");
        }
        if (request.getThumbnailWidth() != null && request.getThumbnailWidth() <= 0) {
            throw new IllegalArgumentException("thumbnailWidth must be positive");
        }
        if (request.getThumbnailHeight() != null && request.getThumbnailHeight() <= 0) {
            throw new IllegalArgumentException("thumbnailHeight must be positive");
        }
        if (request.getTileColumns() != null && request.getTileColumns() <= 0) {
            throw new IllegalArgumentException("tileColumns must be positive");
        }
        if (request.getTileRows() != null && request.getTileRows() <= 0) {
            throw new IllegalArgumentException("tileRows must be positive");
        }
    }

    private long resolveIntervalMillis(Long requestedIntervalMillis, long durationMillis) {
        if (requestedIntervalMillis != null) {
            return requestedIntervalMillis;
        }
        if (durationMillis <= 0L) {
            return minIntervalMillis;
        }

        long target = Math.round(Math.ceil(durationMillis / (double) defaultTimelineFrames));
        long bounded = Math.max(minIntervalMillis, target);
        return roundUpToBucket(bounded, intervalBucketMillis);
    }

    private long roundUpToBucket(long value, long bucketSize) {
        if (bucketSize <= 1L) {
            return value;
        }
        return ((value + bucketSize - 1L) / bucketSize) * bucketSize;
    }

    private List<Long> resolveCapturePositions(long durationMillis, long intervalMillis) {
        if (durationMillis <= 0L) {
            return List.of(0L);
        }

        List<Long> captures = new ArrayList<>();
        long nextPosition = 0L;
        while (nextPosition < durationMillis) {
            captures.add(nextPosition);
            nextPosition += intervalMillis;
        }
        return captures.isEmpty() ? List.of(0L) : List.copyOf(captures);
    }

    private List<VideoTrickplayAssetPlan> buildAssetPlans(
        TrickplayAssetKind kind,
        List<Long> capturePositions,
        long intervalMillis,
        int tileColumns,
        int tileRows,
        int thumbnailWidth,
        int thumbnailHeight,
        int startingAssetIndex
    ) {
        int framesPerAsset = tileColumns * tileRows;
        List<VideoTrickplayAssetPlan> assets = new ArrayList<>();
        for (int offset = 0; offset < capturePositions.size(); offset += framesPerAsset) {
            List<Long> chunk = capturePositions.subList(offset, Math.min(capturePositions.size(), offset + framesPerAsset));
            int actualRows = kind == TrickplayAssetKind.PREVIEW_STRIP ? 1 : tileRows;
            assets.add(new VideoTrickplayAssetPlan(
                kind,
                startingAssetIndex + assets.size(),
                chunk.getFirst(),
                chunk.getLast(),
                intervalMillis,
                chunk.size(),
                tileColumns,
                actualRows,
                thumbnailWidth,
                thumbnailHeight,
                thumbnailWidth * tileColumns,
                thumbnailHeight * actualRows,
                "jpeg",
                "jpg",
                "image/jpeg"
            ));
        }
        return assets;
    }

    private List<VideoTrickplayTimelineEntry> buildTimelineEntries(List<VideoTrickplayAssetPlan> assets) {
        List<VideoTrickplayTimelineEntry> timeline = new ArrayList<>();
        for (VideoTrickplayAssetPlan asset : assets) {
            for (int index = 0; index < asset.getFrameCount(); index++) {
                timeline.add(new VideoTrickplayTimelineEntry(
                    asset.getStartMillis() + (asset.getIntervalMillis() * index),
                    asset.getKind(),
                    asset.getAssetIndex(),
                    index % asset.getTileColumns(),
                    index / asset.getTileColumns()
                ));
            }
        }
        return List.copyOf(timeline);
    }

    private List<String> buildCommand(Path sourcePath, Path outputPath, VideoTrickplayAssetPlan plan) {
        String startSeconds = String.format(Locale.ROOT, "%.3f", plan.getStartMillis() / 1000.0);
        String durationSeconds = String.format(
            Locale.ROOT,
            "%.3f",
            (plan.getIntervalMillis() * plan.getFrameCount()) / 1000.0
        );
        return List.of(
            ffmpegPath,
            "-ss", startSeconds,
            "-t", durationSeconds,
            "-i", sourcePath.toString(),
            "-an",
            "-vf", buildFilterGraph(plan),
            "-frames:v", "1",
            "-q:v", "2",
            "-y",
            outputPath.toString()
        );
    }

    private String buildFilterGraph(VideoTrickplayAssetPlan plan) {
        String fpsValue = String.format(Locale.ROOT, "1/%.3f", plan.getIntervalMillis() / 1000.0);
        return new StringBuilder()
            .append("fps=").append(fpsValue)
            .append(",scale=").append(plan.getThumbnailWidth()).append(':').append(plan.getThumbnailHeight())
            .append(":force_original_aspect_ratio=decrease")
            .append(",pad=").append(plan.getThumbnailWidth()).append(':').append(plan.getThumbnailHeight())
            .append(":(ow-iw)/2:(oh-ih)/2:color=black")
            .append(",tile=").append(plan.getTileColumns()).append('x').append(plan.getTileRows())
            .append(":nb_frames=").append(plan.getFrameCount())
            .toString();
    }

    private Dimensions resolveThumbnailDimensions(
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
        return scaleToBox(sourceWidth, sourceHeight, defaultThumbnailWidth, defaultThumbnailHeight);
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
