package com.nyx.transcode;

import static com.nyx.common.RouteUtilsJava.pageOffset;
import static com.nyx.ffmpeg.MediaProberInterop.probeCachedOrThrow;

import com.nyx.common.ErrorCode;
import com.nyx.common.InvalidJobTransitionException;
import com.nyx.common.MetricsCollector;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.common.QuotaService;
import com.nyx.ffmpeg.FFmpegCommand;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.model.AdaptiveProfile;
import com.nyx.ffmpeg.model.AudioTrackMode;
import com.nyx.ffmpeg.model.HwAccel;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.Profile;
import com.nyx.ffmpeg.model.SegmentDuration;
import com.nyx.ffmpeg.model.SubtitleMode;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.stream.representation.contracts.StreamArtifactKind;
import com.nyx.stream.representation.contracts.StreamCommandOutput;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.stream.representation.contracts.StreamRepresentationTraits;
import com.nyx.stream.representation.contracts.StreamSegmentContainer;
import com.nyx.transcode.contracts.BatchCancelResponse;
import com.nyx.transcode.contracts.BatchJobError;
import com.nyx.transcode.contracts.BatchStatusResponse;
import com.nyx.transcode.contracts.BatchSubmitResponse;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.PlaybackRequestMapper;
import com.nyx.transcode.contracts.SegmentCacheService;
import com.nyx.transcode.contracts.TranscodeApplicationService;
import com.nyx.transcode.contracts.TranscodeExecutionMode;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeJobListing;
import com.nyx.transcode.contracts.TranscodeJobStore;
import com.nyx.transcode.contracts.TranscodeRepresentation;
import com.nyx.transcode.contracts.TranscodeRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unchecked")
public final class TranscodeApplicationCoreService implements TranscodeApplicationService, TranscodeRuntimeContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(TranscodeApplicationCoreService.class);
    private static final int EVENT_BUFFER_CAPACITY = 64;
    private static final StreamRepresentationPolicy REPRESENTATION_POLICY = StreamRepresentationPolicy.defaultPolicy();

    private final TranscodeEngineConfig config;
    private final MediaProber probeService;
    private final SegmentCacheService segmentCache;
    private final ManifestGenerator manifestGenerator;
    private final TranscodeJobStore jobRepository;
    private final PathSecurity pathSecurity;
    private final ExecutorService asyncExecutor;
    private final ScheduledExecutorService cleanupScheduler;
    private final MetricsCollector metricsService;
    private final SegmentRegistry segmentRegistry;
    private final QuotaService quotaService;
    private final TranscodeCommandFactory commandFactory;
    private final TranscodeAttemptRunner attemptRunner;
    private final TranscodeJobOrchestrator jobOrchestrator;

    private final ConcurrentHashMap<String, ReplayPublisher<JobEvent>> jobEvents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProbeResult> jobProbeResults = new ConcurrentHashMap<>();
    private final Set<String> quotaCancelledJobs = ConcurrentHashMap.newKeySet();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Path> segmentOutputDirs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TranscodeRequest> jobRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> jobOutputSizes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedManifest> manifestCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> deferredCleanupTasks = new ConcurrentHashMap<>();

    private volatile TranscodeRuntimeController runtimeController;
    private volatile Consumer<? super JobEvent> onJobEvent;

    public TranscodeApplicationCoreService(
        TranscodeEngineConfig config,
        MediaProber probeService,
        SegmentCacheService segmentCache,
        ManifestGenerator manifestGenerator,
        TranscodeJobStore jobRepository,
        PathSecurity pathSecurity,
        ExecutorService asyncExecutor,
        ScheduledExecutorService cleanupScheduler,
        MetricsCollector metricsService,
        SegmentRegistry segmentRegistry,
        QuotaService quotaService,
        TranscodeCommandFactory commandFactory,
        TranscodeAttemptRunner attemptRunner,
        TranscodeJobOrchestrator jobOrchestrator
    ) {
        this.config = config;
        this.probeService = probeService;
        this.segmentCache = segmentCache;
        this.manifestGenerator = manifestGenerator;
        this.jobRepository = jobRepository;
        this.pathSecurity = pathSecurity;
        this.asyncExecutor = asyncExecutor;
        this.cleanupScheduler = cleanupScheduler;
        this.metricsService = metricsService;
        this.segmentRegistry = segmentRegistry;
        this.quotaService = quotaService;
        this.commandFactory = commandFactory;
        this.attemptRunner = attemptRunner;
        this.jobOrchestrator = jobOrchestrator;
    }

    @Override
    public TranscodeEngineConfig getConfig() {
        return config;
    }

    @Override
    public SegmentCacheService getSegmentCache() {
        return segmentCache;
    }

    @Override
    public TranscodeJobOrchestrator getJobOrchestrator() {
        return jobOrchestrator;
    }

    public ConcurrentHashMap<String, ReplayPublisher<JobEvent>> getJobEvents() {
        return jobEvents;
    }

    @Override
    public ConcurrentHashMap<String, TranscodeRequest> getJobRequests() {
        return jobRequests;
    }

    @Override
    public ConcurrentHashMap<String, ProbeResult> getJobProbeResults() {
        return jobProbeResults;
    }

    @Override
    public ConcurrentHashMap<String, Path> getSegmentOutputDirs() {
        return segmentOutputDirs;
    }

    @Override
    public ConcurrentHashMap<String, AtomicLong> getJobOutputSizes() {
        return jobOutputSizes;
    }

    @Override
    public Set<String> getQuotaCancelledJobs() {
        return quotaCancelledJobs;
    }

    @Override
    public AtomicInteger getConsecutiveFailures() {
        return consecutiveFailures;
    }

    @Override
    public boolean getCircuitBreakerOpen() {
        return consecutiveFailures.get() >= config.getTranscode().getCircuitBreakerThreshold();
    }

    @Override
    public Consumer<JobEvent> getOnJobEvent() {
        return (Consumer<JobEvent>) onJobEvent;
    }

    @Override
    public void setOnJobEvent(Consumer<? super JobEvent> onJobEvent) {
        this.onJobEvent = onJobEvent;
    }

    public void attachRuntime(TranscodeRuntimeController runtimeController) {
        this.runtimeController = runtimeController;
    }

    @Override
    public TranscodeJob submit(TranscodeRequest request, String batchId, String owner) {
        return submitResolvedRequest(request, batchId, owner);
    }

    @Override
    public TranscodeJob submit(PlaybackRequest request, PlaybackDecision decision, String batchId, String owner) {
        return submitResolvedRequest(PlaybackRequestMapper.toTranscodeRequest(request, decision), batchId, owner);
    }

    private TranscodeJob submitResolvedRequest(TranscodeRequest request, String batchId, String owner) {
        pathSecurity.validate(request.getInputPath());
        validateTranscodeRepresentation(request);

        Profile profile;
        try {
            profile = commandFactory.resolveProfile(request.getProfile());
        } catch (NyxException exception) {
            throw sneakyThrow(exception);
        }
        List<TranscodeRepresentation> effectiveRepresentations = resolveRepresentations(request, profile);
        TranscodeJob existing = jobRepository.findActiveBySpecKey(request.getSpecKey());
        if (existing != null) {
            LOGGER.info(
                "Duplicate request for spec {}, returning existing job {}",
                request.getSpecKey(),
                existing.getId()
            );
            return existing;
        }

        Integer maxJobs = null;
        if (owner != null && quotaService != null) {
            int maxConcurrentJobs = quotaService.getMaxConcurrentJobs(owner);
            if (jobRepository.countActiveByOwner(owner) >= maxConcurrentJobs) {
                throw sneakyThrow(new NyxException(ErrorCode.QUOTA_EXCEEDED, "Per-user concurrent job limit exceeded"));
            }
            maxJobs = maxConcurrentJobs;
        }
        Long maxStorageBytes = owner != null && quotaService != null ? quotaService.getMaxStorageBytes(owner) : null;

        ProbeResult probeResult;
        try {
            probeResult = probeCachedOrThrow(probeService, Path.of(request.getInputPath()));
        } catch (Exception exception) {
            throw sneakyThrow(new NyxException(ErrorCode.PROBE_FAILED, "Failed to probe file: " + exception.getMessage(), exception));
        }

        String jobId = UUID.randomUUID().toString().substring(0, 8);
        TranscodeJob job = new TranscodeJob(
            jobId,
            JobStatus.QUEUED,
            request.getInputPath(),
            request.getProfile(),
            request.getRepresentation(),
            effectiveRepresentations,
            request.getExecutionMode(),
            request.getSpecKey(),
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            supportsDashManifest(request.getRepresentation()) ? "/api/v1/transcode/jobs/" + jobId + "/manifest.mpd" : null,
            supportsHlsMasterPlaylist(request.getRepresentation()) ? "/api/v1/transcode/jobs/" + jobId + "/master.m3u8" : null,
            "/api/v1/transcode/jobs/" + jobId + "/progress",
            batchId,
            owner,
            0L
        );

        TranscodeJob created = owner != null && maxJobs != null
            ? jobRepository.createWithQuotaCheck(job, maxJobs, maxStorageBytes)
            : jobRepository.create(job);

        jobEvents.put(jobId, new ReplayPublisher<>(asyncExecutor, EVENT_BUFFER_CAPACITY));
        jobRequests.put(jobId, request);
        jobProbeResults.put(jobId, probeResult);

        if (!requireRuntime().enqueue(created)) {
            jobRepository.updateStatus(jobId, JobStatus.PROBING);
            jobRepository.updateStatus(jobId, JobStatus.FAILED);
            ReplayPublisher<JobEvent> publisher = jobEvents.remove(jobId);
            if (publisher != null) {
                publisher.close();
            }
            jobRequests.remove(jobId);
            jobProbeResults.remove(jobId);
            throw sneakyThrow(new NyxException(ErrorCode.QUEUE_FULL, "Transcoding queue is full"));
        }

        return created;
    }

    private List<TranscodeRepresentation> resolveRepresentations(TranscodeRequest request, Profile profile) {
        return switch (request.getExecutionMode()) {
            case REMUX, AUDIO_TRANSCODE -> List.of();
            default -> {
                List<TranscodeRepresentation> requestRepresentations = request.getRepresentations();
                if (requestRepresentations != null && !requestRepresentations.isEmpty()) {
                    yield requestRepresentations;
                }
                if (profile instanceof AdaptiveProfile adaptiveProfile) {
                    yield adaptiveProfile.getRepresentations().stream()
                        .map(RepresentationMappings::toContractRepresentation)
                        .toList();
                }
                yield List.of();
            }
        };
    }

    private static void validateTranscodeRepresentation(TranscodeRequest request) {
        if (request.getRepresentation() == StreamRepresentation.DIRECT_FILE) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Direct file representation is only valid for direct-play playback delivery"
            ));
        }
    }

    @Override
    public void cancel(String jobId, String owner) {
        if (owner != null) {
            TranscodeJob job = jobRepository.getById(jobId);
            if (job != null && job.getOwner() != null && !owner.equals(job.getOwner())) {
                throw sneakyThrow(new NyxException(ErrorCode.PATH_NOT_ALLOWED, "Not authorized to cancel this job"));
            }
        }
        requireRuntime().cancel(jobId);
        try {
            jobRepository.updateStatus(jobId, JobStatus.CANCELLED);
        } catch (Exception exception) {
            if (!(exception instanceof InvalidJobTransitionException) && !(exception instanceof IllegalArgumentException)) {
                throw sneakyThrow(exception);
            }
        }
        segmentCache.startGracePeriod(jobId);
        manifestCache.remove(jobId);
        scheduleDeferredCleanup(jobId);
        emitRuntimeEvent(jobId, new JobEvent.Error(jobId, "CANCELLED", "Job cancelled by user"));
    }

    @Override
    public BatchSubmitResponse submitBatch(List<TranscodeRequest> requests, String owner) {
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        List<TranscodeJob> submitted = new ArrayList<>();
        List<BatchJobError> errors = new ArrayList<>();
        for (TranscodeRequest request : requests) {
            try {
                submitted.add(submit(request, batchId, owner));
            } catch (Exception exception) {
                if (exception instanceof NyxException nyxException) {
                    errors.add(new BatchJobError(request.getInputPath(), nyxException.getErrorCode().name()));
                } else {
                    errors.add(new BatchJobError(request.getInputPath(), exception.getMessage() == null ? "UNKNOWN" : exception.getMessage()));
                }
            }
        }
        return new BatchSubmitResponse(batchId, submitted, errors);
    }

    @Override
    public BatchStatusResponse getBatchStatus(String batchId, String owner) {
        List<TranscodeJob> allJobs = jobRepository.listByBatchId(batchId);
        if (allJobs.isEmpty()) {
            return null;
        }
        List<TranscodeJob> jobs = owner != null
            ? allJobs.stream().filter(job -> owner.equals(job.getOwner())).toList()
            : allJobs;
        if (jobs.isEmpty()) {
            return null;
        }
        return new BatchStatusResponse(
            batchId,
            jobs.size(),
            (int) jobs.stream().filter(job -> job.getStatus() == JobStatus.QUEUED).count(),
            (int) jobs.stream()
                .filter(job -> job.getStatus() == JobStatus.PROBING || job.getStatus() == JobStatus.TRANSCODING || job.getStatus() == JobStatus.RETRYING)
                .count(),
            (int) jobs.stream().filter(job -> job.getStatus() == JobStatus.COMPLETED).count(),
            (int) jobs.stream().filter(job -> job.getStatus() == JobStatus.FAILED).count(),
            (int) jobs.stream().filter(job -> job.getStatus() == JobStatus.CANCELLED).count(),
            jobs.stream().filter(job -> job.getStatus() == JobStatus.FAILED).toList()
        );
    }

    @Override
    public BatchCancelResponse cancelBatch(String batchId, String owner) {
        List<TranscodeJob> jobs = jobRepository.listByBatchId(batchId);
        if (jobs.isEmpty()) {
            return null;
        }
        List<String> cancelled = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        Set<JobStatus> terminalStatuses = Set.of(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED);
        for (TranscodeJob job : jobs) {
            if (terminalStatuses.contains(job.getStatus())) {
                notFound.add(job.getId());
            } else if (owner != null && job.getOwner() != null && !owner.equals(job.getOwner())) {
                notFound.add(job.getId());
            } else {
                cancel(job.getId(), owner);
                cancelled.add(job.getId());
            }
        }
        return new BatchCancelResponse(cancelled, notFound);
    }

    @Override
    public TranscodeJob getJob(String jobId) {
        return jobRepository.getById(jobId);
    }

    @Override
    public TranscodeJobListing listJobs(int page, int limit, String owner) {
        long offset = pageOffset(page, limit);
        List<TranscodeJob> jobs = jobRepository.listRecent(limit, offset, owner);
        long total = jobRepository.countAll(owner);
        return new TranscodeJobListing(jobs, Math.toIntExact(total), page, limit);
    }

    @Override
    public TranscodeJobListing listJobsFiltered(JobStatus status, Integer sinceMinutes, int page, int limit, String owner) {
        long offset = pageOffset(page, limit);
        List<TranscodeJob> jobs = jobRepository.listFiltered(status, sinceMinutes, limit, offset, owner);
        long total = jobRepository.countFiltered(status, sinceMinutes, owner);
        return new TranscodeJobListing(jobs, Math.toIntExact(total), page, limit, status, sinceMinutes);
    }

    @Override
    public String getLogs(String jobId) {
        TranscodeJob job = jobRepository.getById(jobId);
        if (job == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (job.getStderrInitial() != null) {
            builder.append("=== Initial Attempt ===\n").append(job.getStderrInitial()).append('\n');
        }
        if (job.getStderrFallback() != null) {
            builder.append("=== Fallback Attempt ===\n").append(job.getStderrFallback()).append('\n');
        }
        if (builder.isEmpty()) {
            String activeStderr = requireRuntime().activeStderr(jobId);
            if (activeStderr != null) {
                builder.append(activeStderr);
            }
        }
        return builder.toString();
    }

    @Override
    public String getManifestMpd(String jobId) {
        TranscodeJob job = jobRepository.getById(jobId);
        if (job == null) {
            return null;
        }
        if (!supportsDashManifest(job.getRepresentation())) {
            return null;
        }
        List<SegmentInfo> segments = segmentRegistry.getSegments(jobId);
        CachedManifest cached = manifestCache.get(jobId);
        if (cached != null && cached.segmentCount() == segments.size()) {
            return cached.mpd();
        }
        CachedManifest built = buildManifests(jobId, job, segments);
        manifestCache.put(jobId, built);
        return built.mpd();
    }

    @Override
    public String getManifestM3u8(String jobId) {
        TranscodeJob job = jobRepository.getById(jobId);
        if (job == null) {
            return null;
        }
        if (!supportsHlsMasterPlaylist(job.getRepresentation())) {
            return null;
        }
        List<SegmentInfo> segments = segmentRegistry.getSegments(jobId);
        CachedManifest cached = manifestCache.get(jobId);
        if (cached != null && cached.segmentCount() == segments.size()) {
            return cached.hls();
        }
        CachedManifest built = buildManifests(jobId, job, segments);
        manifestCache.put(jobId, built);
        return built.hls();
    }

    @Override
    public String getSubtitlePlaylist(String jobId, int trackIndex) {
        return jobRepository.getById(jobId) == null ? null : manifestGenerator.generateHlsSubtitleMediaPlaylist(jobId, trackIndex);
    }

    @Override
    public String getHlsMediaPlaylist(String jobId, String representationId) {
        TranscodeJob job = jobRepository.getById(jobId);
        if (job == null) {
            return null;
        }
        if (!supportsHlsMediaPlaylist(job.getRepresentation())) {
            return null;
        }
        List<SegmentInfo> allSegments = segmentRegistry.getSegments(jobId);
        String filteredRepresentationId;
        String initSegmentName;
        StreamRepresentationTraits traits = REPRESENTATION_POLICY.traits(job.getRepresentation());
        if (REPRESENTATION_POLICY.commandOutput(job.getRepresentation()) == StreamCommandOutput.CMAF) {
            String streamIndex = switch (representationId) {
                case "video" -> "0";
                case "audio" -> "1";
                default -> representationId;
            };
            filteredRepresentationId = streamIndex;
            initSegmentName = "init_" + streamIndex + ".mp4";
        } else if (traits.segmentContainer() == StreamSegmentContainer.MPEG_TS) {
            filteredRepresentationId = "video";
            initSegmentName = null;
        } else {
            filteredRepresentationId = representationId;
            initSegmentName = "init.mp4";
        }
        List<SegmentInfo> filteredSegments = allSegments.stream()
            .filter(segment -> filteredRepresentationId.equals(segment.getRepresentationId()))
            .toList();
        return manifestGenerator.generateHlsMediaPlaylist(
            filteredSegments,
            job.getStatus() == JobStatus.COMPLETED,
            config.getTranscode().getSegmentDurationSteadyStateSecs(),
            initSegmentName,
            "segments/"
        );
    }

    @Override
    public Flow.Publisher<JobEvent> eventFlow(String jobId) {
        return jobEvents.get(jobId);
    }

    @Override
    public Path getSegmentOutputDir(String jobId) {
        return segmentOutputDirs.get(jobId);
    }

    public FFmpegCommand buildCommand(
        TranscodeJob job,
        Profile profile,
        ProbeResult probeResult,
        Path outputDir,
        TranscodeRequest request
    ) {
        return commandFactory.buildPrimaryCommand(job, profile, probeResult, outputDir, request);
    }

    public HwAccel parseHwAccel(String value) {
        return commandFactory.parseHwAccel(value);
    }

    public SubtitleMode parseSubtitleMode(String mode, Integer burnTrack) {
        return commandFactory.parseSubtitleMode(mode, burnTrack);
    }

    public AudioTrackMode parseAudioTrackMode(String tracks) {
        return commandFactory.parseAudioTrackMode(tracks);
    }

    private CachedManifest buildManifests(String jobId, TranscodeJob job, List<SegmentInfo> segments) {
        List<TranscodeRepresentation> manifestRepresentations = selectManifestRepresentations(job);

        ProbeResult probeResult = jobProbeResults.get(jobId);
        List<SubtitleTrackInfo> subtitleTracks = probeResult == null
            ? List.of()
            : probeResult.getStreams().getSubtitle().stream()
                .map(track -> new SubtitleTrackInfo(track.getIndex(), track.getLanguage(), track.getTitle()))
                .toList();

        String mpd = supportsDashManifest(job.getRepresentation())
            ? manifestGenerator.generateDashMpd(
                segments,
                List.of(),
                subtitleTracks,
                job.getStatus() == JobStatus.COMPLETED,
                0.0,
                SegmentDuration.ADAPTIVE,
                manifestRepresentations,
                jobId,
                128_000
            )
            : null;

        List<HlsVariantStream> variants;
        if (manifestRepresentations.isEmpty()) {
            variants = List.of(
                new HlsVariantStream(
                    0,
                    "video.m3u8",
                    null,
                    null,
                    null,
                    subtitleTracks.isEmpty() ? null : "subs"
                )
            );
        } else {
            variants = manifestRepresentations.stream()
                .map(representation -> new HlsVariantStream(
                    representation.getBitrateKbps() * 1000,
                    representation.getWidth() + "x" + representation.getHeight() + ".m3u8",
                    representation.getWidth(),
                    representation.getHeight(),
                    null,
                    subtitleTracks.isEmpty() ? null : "subs"
                ))
                .toList();
        }

        String hls = supportsHlsMasterPlaylist(job.getRepresentation())
            ? manifestGenerator.generateHlsMaster(variants, List.of(), subtitleTracks, jobId)
            : null;
        return new CachedManifest(segments.size(), mpd, hls);
    }

    private List<TranscodeRepresentation> selectManifestRepresentations(TranscodeJob job) {
        return switch (job.getExecutionMode()) {
            case REMUX, AUDIO_TRANSCODE -> List.of();
            default -> {
                List<TranscodeRepresentation> jobRepresentations = job.getRepresentations();
                if (REPRESENTATION_POLICY.traits(job.getRepresentation()).segmentContainer() == StreamSegmentContainer.MPEG_TS) {
                    yield jobRepresentations.size() == 1 ? jobRepresentations : List.of();
                }
                yield jobRepresentations;
            }
        };
    }

    private static boolean supportsDashManifest(StreamRepresentation representation) {
        return REPRESENTATION_POLICY.supportsArtifact(representation, StreamArtifactKind.DASH_MANIFEST);
    }

    private static boolean supportsHlsMasterPlaylist(StreamRepresentation representation) {
        return REPRESENTATION_POLICY.supportsArtifact(representation, StreamArtifactKind.HLS_MASTER_PLAYLIST);
    }

    private static boolean supportsHlsMediaPlaylist(StreamRepresentation representation) {
        return REPRESENTATION_POLICY.supportsArtifact(representation, StreamArtifactKind.HLS_MEDIA_PLAYLIST);
    }

    @Override
    public void scheduleDeferredCleanup(String jobId) {
        long gracePeriodMs = config.getTranscode().getSegmentCacheGracePeriodMinutes() * 60_000L;
        ScheduledFuture<?> existingTask = deferredCleanupTasks.remove(jobId);
        if (existingTask != null) {
            existingTask.cancel(false);
        }
        deferredCleanupTasks.put(
            jobId,
            cleanupScheduler.schedule(() -> {
                ReplayPublisher<JobEvent> publisher = jobEvents.remove(jobId);
                if (publisher != null) {
                    publisher.close();
                }
                segmentRegistry.remove(jobId);
                segmentOutputDirs.remove(jobId);
                jobRequests.remove(jobId);
                jobProbeResults.remove(jobId);
                jobOutputSizes.remove(jobId);
                deferredCleanupTasks.remove(jobId);
            }, gracePeriodMs, TimeUnit.MILLISECONDS)
        );
    }

    @Override
    public void emitRuntimeEvent(String jobId, JobEvent event) {
        ReplayPublisher<JobEvent> publisher = jobEvents.get(jobId);
        if (publisher != null) {
            publisher.submit(event);
        }
        Consumer<? super JobEvent> callback = onJobEvent;
        if (callback != null) {
            callback.accept(event);
        }
    }

    @Override
    public void invalidateManifest(String jobId) {
        manifestCache.remove(jobId);
    }

    public void shutdown() {
        deferredCleanupTasks.values().forEach(task -> task.cancel(false));
        deferredCleanupTasks.clear();
        jobEvents.values().forEach(ReplayPublisher::close);
        jobEvents.clear();
        jobProbeResults.clear();
        quotaCancelledJobs.clear();
        segmentOutputDirs.clear();
        jobRequests.clear();
        jobOutputSizes.clear();
        manifestCache.clear();
    }

    private TranscodeRuntimeController requireRuntime() {
        if (runtimeController == null) {
            throw new IllegalStateException("Transcode runtime has not been attached");
        }
        return runtimeController;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private record CachedManifest(int segmentCount, String mpd, String hls) {}
}
