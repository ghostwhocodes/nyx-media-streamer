package com.nyx.transcode;

import static com.nyx.ffmpeg.MediaProberInterop.probeCachedOrThrow;

import com.nyx.common.MetricsCollector;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.model.Profile;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.SegmentCacheService;
import com.nyx.transcode.contracts.TranscodeExecutionMode;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeJobStore;
import com.nyx.transcode.contracts.TranscodeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TranscodeJobOrchestrator {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranscodeJobOrchestrator.class);

    private final TranscodeEngineConfig config;
    private final MediaProber probeService;
    private final TranscodeJobStore jobStore;
    private final SegmentRegistry segmentRegistry;
    private final TranscodeCommandFactory commandFactory;
    private final TranscodeAttemptRunner attemptRunner;
    private final MetricsCollector metricsService;

    public TranscodeJobOrchestrator(
        TranscodeEngineConfig config,
        MediaProber probeService,
        TranscodeJobStore jobStore,
        SegmentRegistry segmentRegistry,
        TranscodeCommandFactory commandFactory,
        TranscodeAttemptRunner attemptRunner,
        MetricsCollector metricsService
    ) {
        this.config = config;
        this.probeService = probeService;
        this.jobStore = jobStore;
        this.segmentRegistry = segmentRegistry;
        this.commandFactory = commandFactory;
        this.attemptRunner = attemptRunner;
        this.metricsService = metricsService;
    }

    public void process(
        TranscodeJob job,
        int workerIndex,
        ConcurrentHashMap<String, TranscodeRequest> jobRequests,
        ConcurrentHashMap<String, ProbeResult> jobProbeResults,
        ConcurrentHashMap<String, Path> segmentOutputDirs,
        ConcurrentHashMap<String, AtomicLong> jobOutputSizes,
        Set<String> quotaCancelledJobs,
        ConcurrentHashMap<String, FFmpegProcess> activeProcesses,
        AtomicInteger consecutiveFailures,
        SegmentCacheService segmentCache,
        Consumer<String> onManifestInvalidated,
        BiConsumer<String, JobEvent> emitEvent
    ) {
        String jobId = job.getId();
        LOGGER.info("Worker {} processing job {}", workerIndex, jobId);
        long jobStartNanos = System.nanoTime();

        try {
            jobStore.updateStatus(jobId, JobStatus.PROBING);

            ProbeResult probeResult = jobProbeResults.get(jobId);
            if (probeResult == null) {
                try {
                    probeResult = probeCachedOrThrow(probeService, Path.of(job.getInputPath()));
                } catch (Exception exception) {
                    jobStore.updateStatus(jobId, JobStatus.FAILED);
                    consecutiveFailures.incrementAndGet();
                    emitEvent.accept(
                        jobId,
                        new JobEvent.Error(jobId, "PROBE_FAILED", exception.getMessage() == null ? "Probe failed" : exception.getMessage())
                    );
                    return;
                }
            }

            jobStore.updateStatus(jobId, JobStatus.TRANSCODING);

            Profile profile = commandFactory.resolveProfile(job.getProfile());
            Path outputDir = createOutputDir(jobId);
            segmentOutputDirs.put(jobId, outputDir);

            long usableSpace = Files.getFileStore(outputDir).getUsableSpace();
            if (usableSpace < config.getTranscode().getMinFreeDiskBytes()) {
                long availableMb = usableSpace / 1_048_576L;
                long requiredMb = config.getTranscode().getMinFreeDiskBytes() / 1_048_576L;
                jobStore.updateStatus(jobId, JobStatus.FAILED);
                consecutiveFailures.incrementAndGet();
                emitEvent.accept(
                    jobId,
                    new JobEvent.Error(
                        jobId,
                        "TRANSCODE_FAILED",
                        "Insufficient disk space: " + availableMb + " MB available, " + requiredMb + " MB required"
                    )
                );
                return;
            }

            TranscodeRequest request = jobRequests.get(jobId);
            boolean remuxPlanned = job.getExecutionMode() == TranscodeExecutionMode.REMUX;
            boolean audioTranscodePlanned = job.getExecutionMode() == TranscodeExecutionMode.AUDIO_TRANSCODE;
            int maxAttempts = 1 + config.getTranscode().getMaxRetries();
            int effectiveMaxAttempts = remuxPlanned || audioTranscodePlanned ? 1 : maxAttempts;
            boolean succeeded = false;
            String accumulatedFallbackStderr = "";

            for (int attempt = 0; attempt < effectiveMaxAttempts; attempt += 1) {
                com.nyx.ffmpeg.FFmpegCommand command;
                if (attempt == 0) {
                    if (remuxPlanned) {
                        command = buildRemuxCommand(job, probeResult, outputDir, request);
                    } else if (audioTranscodePlanned) {
                        command = buildAudioTranscodeCommand(job, profile, probeResult, outputDir, request);
                    } else {
                        command = buildCommand(job, profile, probeResult, outputDir, request);
                    }
                } else {
                    Path retryDir = createOutputDir(jobId + "_retry" + attempt);
                    segmentOutputDirs.put(jobId, retryDir);
                    segmentRegistry.clear(jobId);
                    command = buildFallbackCommand(job, probeResult, retryDir);
                }

                boolean success = attemptRunner.execute(
                    jobId,
                    command,
                    probeResult,
                    segmentOutputDirs.getOrDefault(jobId, outputDir),
                    job.getOwner(),
                    jobStore,
                    segmentCache,
                    segmentRegistry,
                    activeProcesses,
                    jobOutputSizes,
                    quotaCancelledJobs,
                    () -> onManifestInvalidated.accept(jobId),
                    event -> emitEvent.accept(jobId, event)
                );

                if (success) {
                    succeeded = true;
                    break;
                }

                if (quotaCancelledJobs.contains(jobId)) {
                    break;
                }

                FFmpegProcess activeProcess = activeProcesses.get(jobId);
                String stderr = activeProcess == null ? "" : activeProcess.stderrCapture();
                if (attempt == 0) {
                    jobStore.storeStderr(jobId, stderr, null);
                } else {
                    accumulatedFallbackStderr = accumulatedFallbackStderr.isEmpty()
                        ? stderr
                        : accumulatedFallbackStderr + "\n\n" + stderr;
                    jobStore.storeStderr(jobId, null, accumulatedFallbackStderr);
                }

                int nextAttempt = attempt + 1;
                if (nextAttempt < effectiveMaxAttempts) {
                    jobStore.updateRetryCount(jobId, nextAttempt);

                    long backoffMs = config.getTranscode().getRetryBackoffMs() * (1L << (nextAttempt - 1));
                    LOGGER.info(
                        "Job {} attempt {} failed, retrying (attempt {}/{}) after {}ms",
                        jobId,
                        attempt,
                        nextAttempt,
                        effectiveMaxAttempts,
                        backoffMs
                    );

                    jobStore.updateStatus(jobId, JobStatus.RETRYING);
                    emitEvent.accept(
                        jobId,
                        new JobEvent.Retry(
                            jobId,
                            nextAttempt,
                            "FFmpeg failed, retrying with safe fallback (backoff " + backoffMs + "ms)"
                        )
                    );

                    Thread.sleep(backoffMs);
                    jobStore.updateStatus(jobId, JobStatus.TRANSCODING);
                }
            }

            AtomicLong outputSize = jobOutputSizes.get(jobId);
            if (outputSize != null) {
                jobStore.updateOutputSize(jobId, outputSize.get());
            }

            if (quotaCancelledJobs.contains(jobId)) {
                quotaCancelledJobs.remove(jobId);
                jobStore.updateStatus(jobId, JobStatus.CANCELLED);
                emitEvent.accept(jobId, new JobEvent.Error(jobId, "QUOTA_EXCEEDED", "Per-user storage quota exceeded"));
            } else if (succeeded) {
                jobStore.updateStatus(jobId, JobStatus.COMPLETED);
                consecutiveFailures.set(0);
                if (metricsService != null) {
                    metricsService.recordJobDuration(System.nanoTime() - jobStartNanos);
                }
                List<SegmentInfo> segments = segmentRegistry.getSegments(jobId);
                emitEvent.accept(jobId, new JobEvent.Complete(jobId, probeResult.getDurationSecs(), segments.size()));
            } else {
                jobStore.updateStatus(jobId, JobStatus.FAILED);
                consecutiveFailures.incrementAndGet();
                String attemptsMessage = switch (job.getExecutionMode()) {
                    case REMUX -> "FFmpeg remux failed";
                    case AUDIO_TRANSCODE -> "FFmpeg audio transcode failed";
                    case SUBTITLE_BURN_IN -> "FFmpeg subtitle burn-in transcode failed";
                    case VIDEO_TRANSCODE -> config.getTranscode().getMaxRetries() == 0
                        ? "FFmpeg failed"
                        : "FFmpeg failed after " + (1 + config.getTranscode().getMaxRetries()) + " attempts";
                };
                emitEvent.accept(jobId, new JobEvent.Error(jobId, "TRANSCODE_FAILED", attemptsMessage));
            }
        } catch (Exception exception) {
            LOGGER.error("Job {} failed with exception", jobId, exception);
            try {
                jobStore.updateStatus(jobId, JobStatus.FAILED);
            } catch (Exception updateException) {
                LOGGER.warn("Failed to update job status to FAILED for job {}: {}", jobId, updateException.getMessage());
            }
            consecutiveFailures.incrementAndGet();
            emitEvent.accept(
                jobId,
                new JobEvent.Error(jobId, "TRANSCODE_FAILED", exception.getMessage() == null ? "Unknown error" : exception.getMessage())
            );
        }
    }

    private com.nyx.ffmpeg.FFmpegCommand buildCommand(
        TranscodeJob job,
        Profile profile,
        ProbeResult probeResult,
        Path outputDir,
        TranscodeRequest request
    ) {
        return commandFactory.buildPrimaryCommand(job, profile, probeResult, outputDir, request);
    }

    private com.nyx.ffmpeg.FFmpegCommand buildRemuxCommand(
        TranscodeJob job,
        ProbeResult probeResult,
        Path outputDir,
        TranscodeRequest request
    ) {
        return commandFactory.buildRemuxCommand(job, probeResult, outputDir, request);
    }

    private com.nyx.ffmpeg.FFmpegCommand buildAudioTranscodeCommand(
        TranscodeJob job,
        Profile profile,
        ProbeResult probeResult,
        Path outputDir,
        TranscodeRequest request
    ) {
        return commandFactory.buildAudioTranscodeCommand(job, profile, probeResult, outputDir, request);
    }

    private com.nyx.ffmpeg.FFmpegCommand buildFallbackCommand(
        TranscodeJob job,
        ProbeResult probeResult,
        Path outputDir
    ) {
        return commandFactory.buildFallbackCommand(job, probeResult, outputDir);
    }

    private Path createOutputDir(String jobId) throws Exception {
        Path directory = Path.of(config.getDatabaseDir().toString(), "segments", jobId);
        Files.createDirectories(directory);
        return directory;
    }
}
