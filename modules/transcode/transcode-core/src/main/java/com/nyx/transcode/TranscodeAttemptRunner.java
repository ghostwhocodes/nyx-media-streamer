package com.nyx.transcode;

import static com.nyx.transcode.watch.SegmentWatcherFactory.createWatcher;

import com.nyx.common.MetricsCollector;
import com.nyx.common.QuotaService;
import com.nyx.ffmpeg.FFmpegCommand;
import com.nyx.ffmpeg.model.OutputFormat;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.SegmentCacheService;
import com.nyx.transcode.contracts.TranscodeJobStore;
import com.nyx.transcode.watch.SegmentWatcher;
import com.nyx.transcode.watch.WatchStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TranscodeAttemptRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranscodeAttemptRunner.class);
    private static final int OUTPUT_SIZE_DB_UPDATE_INTERVAL = 10;
    private static final Pattern CMAF_CHUNK_PATTERN = Pattern.compile("^chunk_(\\d+)_");

    private final TranscodeEngineConfig config;
    private final ExecutorService asyncExecutor;
    private final MetricsCollector metricsService;
    private final QuotaService quotaService;

    public TranscodeAttemptRunner(
        TranscodeEngineConfig config,
        ExecutorService asyncExecutor,
        MetricsCollector metricsService,
        QuotaService quotaService
    ) {
        this.config = config;
        this.asyncExecutor = asyncExecutor;
        this.metricsService = metricsService;
        this.quotaService = quotaService;
    }

    public boolean execute(
        String jobId,
        FFmpegCommand command,
        ProbeResult probeResult,
        Path outputDir,
        String owner,
        TranscodeJobStore jobStore,
        SegmentCacheService segmentCache,
        SegmentRegistry segmentRegistry,
        ConcurrentHashMap<String, FFmpegProcess> processRegistry,
        ConcurrentHashMap<String, AtomicLong> jobOutputSizes,
        Set<String> quotaCancelledJobs,
        Runnable onManifestInvalidated,
        Consumer<JobEvent> emitEvent
    ) {
        FFmpegProcess ffmpegProcess = new FFmpegProcess(
            command,
            config.getFfmpeg().getPath(),
            asyncExecutor,
            () -> {
                if (metricsService != null) {
                    metricsService.ffmpegProcessStarted();
                }
            },
            () -> {
                if (metricsService != null) {
                    metricsService.ffmpegProcessFinished();
                }
            },
            5_000L,
            (nanos, success) -> {
                if (metricsService != null) {
                    metricsService.recordFfmpegProcessDuration(nanos, success);
                }
            }
        );
        processRegistry.put(jobId, ffmpegProcess);

        try {
            ffmpegProcess.start();
        } catch (RuntimeException | Error exception) {
            return false;
        } catch (Throwable throwable) {
            return false;
        }

        Future<?> progressFuture = asyncExecutor.submit(() -> {
            try {
                ffmpegProcess.progressFlow().collect(progress ->
                    emitEvent.accept(
                        new JobEvent.Progress(jobId, progress.progressPercent(), progress.speed(), progress.fps())
                    )
                );
            } catch (Exception exception) {
                LOGGER.debug("Progress collection ended for job {}: {}", jobId, exception.getMessage());
            }
        });

        WatchStrategy strategy;
        try {
            strategy = WatchStrategy.valueOf(config.getFfmpeg().getWatchStrategy().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            strategy = WatchStrategy.POLLING;
        }

        SegmentWatcher watcher = createWatcher(strategy, config.getFfmpeg().getSegmentWatchPollIntervalMs());
        Future<?> watcherFuture = asyncExecutor.submit(() -> {
            try {
                watcher.watch(outputDir).collect(segPath -> {
                    String fileName = segPath.getFileName().toString();
                    if (!fileName.endsWith(".m4s") && !fileName.endsWith(".mp4") && !fileName.endsWith(".ts")) {
                        return;
                    }
                    if (fileName.startsWith("init")) {
                        return;
                    }

                    segmentCache.register(segPath, jobId);

                    long segmentSize;
                    try {
                        segmentSize = Files.size(segPath);
                    } catch (Exception ignored) {
                        segmentSize = 0L;
                    }
                    AtomicLong totalSize = jobOutputSizes.computeIfAbsent(jobId, ignored -> new AtomicLong(0L));
                    totalSize.addAndGet(segmentSize);

                    String representationId;
                    if (command.getOutputFormat() instanceof OutputFormat.Cmaf) {
                        Matcher matcher = CMAF_CHUNK_PATTERN.matcher(fileName);
                        representationId = matcher.find() ? matcher.group(1) : "0";
                    } else {
                        representationId = "video";
                    }

                    SegmentInfo segmentInfo = new SegmentInfo(
                        fileName,
                        representationId,
                        (double) config.getTranscode().getSegmentDurationSteadyStateSecs(),
                        segmentRegistry.count(jobId)
                    );
                    segmentRegistry.register(jobId, segmentInfo);
                    onManifestInvalidated.run();

                    int segmentCount = segmentRegistry.count(jobId);
                    jobStore.updateProgress(jobId, segmentCount);

                    if (segmentCount % OUTPUT_SIZE_DB_UPDATE_INTERVAL == 0) {
                        AtomicLong currentSize = jobOutputSizes.get(jobId);
                        if (currentSize != null) {
                            jobStore.updateOutputSize(jobId, currentSize.get());

                            if (owner != null && quotaService != null) {
                                long maxBytes = quotaService.getMaxStorageBytes(owner);
                                long totalUsed = jobStore.sumStorageByOwner(owner);
                                if (totalUsed > maxBytes) {
                                    LOGGER.warn(
                                        "Job {} exceeded storage quota for owner {}: {} bytes used, {} bytes allowed",
                                        jobId,
                                        owner,
                                        totalUsed,
                                        maxBytes
                                    );
                                    quotaCancelledJobs.add(jobId);
                                    FFmpegProcess runningProcess = processRegistry.get(jobId);
                                    if (runningProcess != null) {
                                        runningProcess.cancel();
                                    }
                                }
                            }
                        }
                    }

                    emitEvent.accept(new JobEvent.Segment(jobId, fileName, representationId, segmentInfo.getDurationSecs()));
                });
            } catch (Exception exception) {
                LOGGER.debug("Segment watcher ended for job {}: {}", jobId, exception.getMessage());
            }
        });

        try {
            return ffmpegProcess.awaitTerminalState() == ProcessState.COMPLETED;
        } finally {
            watcher.close();
            awaitTask(progressFuture);
            awaitTask(watcherFuture);
        }
    }

    private void awaitTask(Future<?> task) {
        try {
            task.get(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            task.cancel(true);
        }
    }
}
