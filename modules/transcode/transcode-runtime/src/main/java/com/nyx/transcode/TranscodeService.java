package com.nyx.transcode;

import com.nyx.common.ManagedService;
import com.nyx.common.MetricsCollector;
import com.nyx.common.PathSecurity;
import com.nyx.common.QuotaService;
import com.nyx.ffmpeg.FFmpegCommand;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.model.AudioTrackMode;
import com.nyx.ffmpeg.model.HwAccel;
import com.nyx.ffmpeg.model.Profile;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.SubtitleMode;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.transcode.contracts.BatchCancelResponse;
import com.nyx.transcode.contracts.BatchStatusResponse;
import com.nyx.transcode.contracts.BatchSubmitResponse;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.ManagedTranscodeApplicationService;
import com.nyx.transcode.contracts.SegmentCacheService;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeJobListing;
import com.nyx.transcode.contracts.TranscodeJobStore;
import com.nyx.transcode.contracts.TranscodeRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class TranscodeService implements ManagedService, ManagedTranscodeApplicationService {
    private final TranscodeApplicationCoreService applicationService;
    private final LocalTranscodeRuntime runtime;
    private final ExecutorService asyncExecutor;
    private final ScheduledExecutorService ownedCleanupScheduler;

    private TranscodeService(Components components) {
        this(
            components.applicationService(),
            components.runtime(),
            components.asyncExecutor(),
            components.ownedCleanupScheduler()
        );
    }

    public TranscodeService(
        TranscodeApplicationCoreService applicationService,
        LocalTranscodeRuntime runtime,
        ExecutorService asyncExecutor,
        ScheduledExecutorService ownedCleanupScheduler
    ) {
        this.applicationService = applicationService;
        this.runtime = runtime;
        this.asyncExecutor = asyncExecutor;
        this.ownedCleanupScheduler = ownedCleanupScheduler;
        this.applicationService.attachRuntime(runtime);
    }

    public TranscodeService(
        TranscodeEngineConfig config,
        MediaProber probeService,
        SegmentCacheService segmentCache,
        ManifestGenerator manifestGenerator,
        TranscodeJobStore jobRepository,
        PathSecurity pathSecurity
    ) {
        this(
            config,
            probeService,
            segmentCache,
            manifestGenerator,
            jobRepository,
            pathSecurity,
            null,
            null,
            new InMemorySegmentRegistry(),
            null,
            new TranscodeCommandFactory()
        );
    }

    public TranscodeService(
        TranscodeEngineConfig config,
        MediaProber probeService,
        SegmentCacheService segmentCache,
        ManifestGenerator manifestGenerator,
        TranscodeJobStore jobRepository,
        PathSecurity pathSecurity,
        ScheduledExecutorService cleanupScheduler,
        MetricsCollector metricsService,
        SegmentRegistry segmentRegistry,
        QuotaService quotaService,
        TranscodeCommandFactory commandFactory
    ) {
        this(createComponents(
            config,
            probeService,
            segmentCache,
            manifestGenerator,
            jobRepository,
            pathSecurity,
            cleanupScheduler,
            metricsService,
            segmentRegistry,
            quotaService,
            commandFactory
        ));
    }

    public ConcurrentHashMap<String, ReplayPublisher<JobEvent>> getJobEvents() {
        return applicationService.getJobEvents();
    }

    public ConcurrentHashMap<String, Path> getSegmentOutputDirs() {
        return applicationService.getSegmentOutputDirs();
    }

    public ConcurrentHashMap<String, FFmpegProcess> getActiveProcesses() {
        return runtime.activeProcesses;
    }

    public ConcurrentHashMap<String, ProbeResult> getJobProbeResults() {
        return applicationService.getJobProbeResults();
    }

    public Set<String> getQuotaCancelledJobs() {
        return applicationService.getQuotaCancelledJobs();
    }

    public AtomicInteger getConsecutiveFailures() {
        return applicationService.getConsecutiveFailures();
    }

    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    public FFmpegCommand buildCommand(
        TranscodeJob job,
        Profile profile,
        ProbeResult probeResult,
        Path outputDir,
        TranscodeRequest request
    ) {
        return applicationService.buildCommand(job, profile, probeResult, outputDir, request);
    }

    public HwAccel parseHwAccel(String value) {
        return applicationService.parseHwAccel(value);
    }

    public SubtitleMode parseSubtitleMode(String mode, Integer burnTrack) {
        return applicationService.parseSubtitleMode(mode, burnTrack);
    }

    public AudioTrackMode parseAudioTrackMode(String tracks) {
        return applicationService.parseAudioTrackMode(tracks);
    }

    @Override
    public void shutdown() {
        runtime.shutdown();
        applicationService.shutdown();
        asyncExecutor.shutdownNow();
        if (ownedCleanupScheduler != null) {
            ownedCleanupScheduler.shutdownNow();
        }
    }

    @Override
    public boolean getCircuitBreakerOpen() {
        return applicationService.getCircuitBreakerOpen();
    }

    @Override
    public Consumer<JobEvent> getOnJobEvent() {
        return applicationService.getOnJobEvent();
    }

    @Override
    public void setOnJobEvent(Consumer<? super JobEvent> onJobEvent) {
        applicationService.setOnJobEvent(onJobEvent);
    }

    @Override
    public Flow.Publisher<JobEvent> eventFlow(String jobId) {
        return applicationService.eventFlow(jobId);
    }

    @Override
    public TranscodeJob submit(TranscodeRequest request, String batchId, String owner) {
        return applicationService.submit(request, batchId, owner);
    }

    @Override
    public TranscodeJob submit(PlaybackRequest request, PlaybackDecision decision, String batchId, String owner) {
        return applicationService.submit(request, decision, batchId, owner);
    }

    @Override
    public BatchSubmitResponse submitBatch(List<TranscodeRequest> requests, String owner) {
        return applicationService.submitBatch(requests, owner);
    }

    @Override
    public void cancel(String jobId, String owner) {
        applicationService.cancel(jobId, owner);
    }

    @Override
    public BatchCancelResponse cancelBatch(String batchId, String owner) {
        return applicationService.cancelBatch(batchId, owner);
    }

    @Override
    public BatchStatusResponse getBatchStatus(String batchId, String owner) {
        return applicationService.getBatchStatus(batchId, owner);
    }

    @Override
    public TranscodeJob getJob(String jobId) {
        return applicationService.getJob(jobId);
    }

    @Override
    public TranscodeJobListing listJobs(int page, int limit, String owner) {
        return applicationService.listJobs(page, limit, owner);
    }

    @Override
    public TranscodeJobListing listJobsFiltered(
        JobStatus status,
        Integer sinceMinutes,
        int page,
        int limit,
        String owner
    ) {
        return applicationService.listJobsFiltered(status, sinceMinutes, page, limit, owner);
    }

    @Override
    public String getLogs(String jobId) {
        return applicationService.getLogs(jobId);
    }

    @Override
    public String getManifestMpd(String jobId) {
        return applicationService.getManifestMpd(jobId);
    }

    @Override
    public String getManifestM3u8(String jobId) {
        return applicationService.getManifestM3u8(jobId);
    }

    @Override
    public String getSubtitlePlaylist(String jobId, int trackIndex) {
        return applicationService.getSubtitlePlaylist(jobId, trackIndex);
    }

    @Override
    public String getHlsMediaPlaylist(String jobId, String representationId) {
        return applicationService.getHlsMediaPlaylist(jobId, representationId);
    }

    @Override
    public Path getSegmentOutputDir(String jobId) {
        return applicationService.getSegmentOutputDir(jobId);
    }

    private static Components createComponents(
        TranscodeEngineConfig config,
        MediaProber probeService,
        SegmentCacheService segmentCache,
        ManifestGenerator manifestGenerator,
        TranscodeJobStore jobRepository,
        PathSecurity pathSecurity,
        ScheduledExecutorService cleanupScheduler,
        MetricsCollector metricsService,
        SegmentRegistry segmentRegistry,
        QuotaService quotaService,
        TranscodeCommandFactory commandFactory
    ) {
        ScheduledExecutorService ownedCleanupScheduler = cleanupScheduler != null
            ? cleanupScheduler
            : Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "nyx-transcode-cleanup");
                thread.setDaemon(true);
                return thread;
            });
        ExecutorService runtimeExecutor = Executors.newFixedThreadPool(config.getFfmpeg().getMaxConcurrentJobs(), runnable -> {
            Thread thread = new Thread(runnable, "nyx-transcode-runtime");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorService asyncExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("nyx-transcode-async-", 0).factory()
        );
        TranscodeAttemptRunner attemptRunner = new TranscodeAttemptRunner(
            config,
            asyncExecutor,
            metricsService,
            quotaService
        );
        TranscodeJobOrchestrator jobOrchestrator = new TranscodeJobOrchestrator(
            config,
            probeService,
            jobRepository,
            segmentRegistry,
            commandFactory,
            attemptRunner,
            metricsService
        );
        TranscodeApplicationCoreService applicationService = new TranscodeApplicationCoreService(
            config,
            probeService,
            segmentCache,
            manifestGenerator,
            jobRepository,
            pathSecurity,
            asyncExecutor,
            ownedCleanupScheduler,
            metricsService,
            segmentRegistry,
            quotaService,
            commandFactory,
            attemptRunner,
            jobOrchestrator
        );
        LocalTranscodeRuntime runtime = new LocalTranscodeRuntime(runtimeExecutor, applicationService);
        return new Components(
            applicationService,
            runtime,
            asyncExecutor,
            cleanupScheduler == null ? ownedCleanupScheduler : null
        );
    }

    private record Components(
        TranscodeApplicationCoreService applicationService,
        LocalTranscodeRuntime runtime,
        ExecutorService asyncExecutor,
        ScheduledExecutorService ownedCleanupScheduler
    ) {}
}
