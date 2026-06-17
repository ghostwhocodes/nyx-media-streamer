package com.nyx.playback;

import com.nyx.common.ErrorCode;
import com.nyx.common.ManagedService;
import com.nyx.common.NyxException;
import com.nyx.media.contracts.MediaKind;
import com.nyx.playback.contracts.MediaSessionPlaybackEvent;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.nyx.playback.contracts.MediaSessionTelemetry;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackDecisionService;
import com.nyx.playback.contracts.PlaybackLifecycleEndReason;
import com.nyx.playback.contracts.PlaybackLifecyclePhase;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSession;
import com.nyx.playback.contracts.PlaybackSessionArtifacts;
import com.nyx.playback.contracts.PlaybackSessionLifecycle;
import com.nyx.playback.contracts.PlaybackSessionService;
import com.nyx.playback.contracts.PlaybackSessionState;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeApplicationService;
import com.nyx.transcode.contracts.TranscodeJob;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalPlaybackSessionService implements PlaybackSessionService, ManagedService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalPlaybackSessionService.class);
    private static final long DEFAULT_TERMINAL_SESSION_RETENTION_MS = 5 * 60_000L;

    private final PlaybackDecisionService playbackDecisionService;
    private final TranscodeApplicationService transcodeService;
    private final long terminalSessionRetentionMs;
    private final Clock clock;
    private final ConcurrentHashMap<String, PlaybackSessionRecord> sessions = new ConcurrentHashMap<>();
    private final boolean ownsCleanupScheduler;
    private final ScheduledExecutorService scheduler;

    public LocalPlaybackSessionService(
        PlaybackDecisionService playbackDecisionService,
        TranscodeApplicationService transcodeService
    ) {
        this(
            playbackDecisionService,
            transcodeService,
            null,
            DEFAULT_TERMINAL_SESSION_RETENTION_MS,
            Clock.systemUTC()
        );
    }

    public LocalPlaybackSessionService(
        PlaybackDecisionService playbackDecisionService,
        TranscodeApplicationService transcodeService,
        long terminalSessionRetentionMs
    ) {
        this(playbackDecisionService, transcodeService, null, terminalSessionRetentionMs, Clock.systemUTC());
    }

    public LocalPlaybackSessionService(
        PlaybackDecisionService playbackDecisionService,
        TranscodeApplicationService transcodeService,
        ScheduledExecutorService cleanupScheduler
    ) {
        this(
            playbackDecisionService,
            transcodeService,
            cleanupScheduler,
            DEFAULT_TERMINAL_SESSION_RETENTION_MS,
            Clock.systemUTC()
        );
    }

    public LocalPlaybackSessionService(
        PlaybackDecisionService playbackDecisionService,
        TranscodeApplicationService transcodeService,
        ScheduledExecutorService cleanupScheduler,
        long terminalSessionRetentionMs
    ) {
        this(playbackDecisionService, transcodeService, cleanupScheduler, terminalSessionRetentionMs, Clock.systemUTC());
    }

    public LocalPlaybackSessionService(
        PlaybackDecisionService playbackDecisionService,
        TranscodeApplicationService transcodeService,
        ScheduledExecutorService cleanupScheduler,
        long terminalSessionRetentionMs,
        Clock clock
    ) {
        this.playbackDecisionService = playbackDecisionService;
        this.transcodeService = transcodeService;
        this.terminalSessionRetentionMs = terminalSessionRetentionMs;
        this.clock = clock;
        this.ownsCleanupScheduler = cleanupScheduler == null;
        this.scheduler = cleanupScheduler != null
            ? cleanupScheduler
            : Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "nyx-playback-session-cleanup");
                thread.setDaemon(true);
                return thread;
            });
    }

    @Override
    public PlaybackSession openSession(PlaybackRequest request, String owner) {
        PlaybackDecision decision = playbackDecisionService.decide(request);
        StreamingProtocol preferredProtocol = decision.stream().protocol();
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String createdAt = Instant.now(clock).toString();

        logDecision(sessionId, owner, decision);

        PlaybackSessionRecord record;
        if (decision.mode() == PlaybackMode.DIRECT_PLAY) {
            record = new PlaybackSessionRecord(
                sessionId,
                owner,
                request,
                createdAt,
                StreamingProtocol.FILE,
                decision,
                Path.of(request.source().path()),
                null
            );
            record.readyAt = createdAt;
            record.lifecycleProgressPercent = 100.0;
        } else {
            TranscodeJob job = callTranscodeSubmit(request, decision, owner);
            record = new PlaybackSessionRecord(
                sessionId,
                owner,
                request,
                createdAt,
                preferredProtocol,
                decision,
                null,
                job.getId()
            );
        }
        record.playbackProgressPercent = initialProgressPercent(record.positionMillis, record.durationMillis);
        if (record.playbackProgressPercent == null) {
            record.playbackProgressPercent = 0.0;
        }

        sessions.put(sessionId, record);
        observeJobLifecycle(record);
        return buildSession(record);
    }

    @Override
    public PlaybackSession getSession(String sessionId, String owner) {
        PlaybackSessionRecord record = sessions.get(sessionId);
        if (record == null || !isAccessible(record, owner)) {
            return null;
        }
        return buildSession(record);
    }

    @Override
    public PlaybackSession reportPlayback(String sessionId, MediaSessionPlaybackReport report, String owner) {
        PlaybackSessionRecord record = sessions.get(sessionId);
        if (record == null) {
            return sneakyThrow(nyxException(ErrorCode.JOB_NOT_FOUND, "Playback session not found: " + sessionId));
        }
        if (!isAccessible(record, owner)) {
            return sneakyThrow(nyxException(
                ErrorCode.PATH_NOT_ALLOWED,
                "Not authorized to report playback for this playback session"
            ));
        }
        if (record.closed) {
            return sneakyThrow(nyxException(
                ErrorCode.INVALID_REQUEST,
                "Playback session is no longer active: " + sessionId
            ));
        }
        validateReportIdentity(record, report);

        if (report.positionMillis() != null) {
            if (report.positionMillis() < 0) {
                return sneakyThrow(nyxException(
                    ErrorCode.INVALID_REQUEST,
                    "Playback session positionMillis must be non-negative"
                ));
            }
            record.positionMillis = report.positionMillis();
        }
        if (report.durationMillis() != null) {
            if (report.durationMillis() <= 0) {
                return sneakyThrow(nyxException(
                    ErrorCode.INVALID_REQUEST,
                    "Playback session durationMillis must be positive when provided"
                ));
            }
            record.durationMillis = report.durationMillis();
        }

        record.objectId = report.objectId() != null ? report.objectId() : record.objectId;
        record.mediaKind = report.mediaKind() != null ? report.mediaKind() : record.mediaKind;
        String occurredAt = report.occurredAt() != null ? report.occurredAt() : nowIso();
        record.lastEvent = report.event();
        record.lastEventAt = occurredAt;
        record.clientName = report.clientName() != null ? report.clientName() : record.clientName;
        record.deviceName = report.deviceName() != null ? report.deviceName() : record.deviceName;
        record.playbackContext = report.playbackContext() != null ? report.playbackContext() : record.playbackContext;
        record.playbackProgressPercent = resolvePlaybackProgressPercent(
            record.positionMillis,
            record.durationMillis,
            report.event(),
            record.playbackProgressPercent
        );

        switch (report.event()) {
            case STARTED, HEARTBEAT -> {
            }
            case STOPPED -> terminateSession(
                record,
                occurredAt,
                PlaybackLifecycleEndReason.CLIENT_REQUESTED,
                MediaSessionPlaybackEvent.STOPPED,
                owner
            );
            case COMPLETED -> {
                record.playbackProgressPercent = 100.0;
                if (record.durationMillis != null) {
                    record.positionMillis = record.durationMillis;
                }
                terminateSession(
                    record,
                    occurredAt,
                    PlaybackLifecycleEndReason.PLAYBACK_COMPLETED,
                    MediaSessionPlaybackEvent.COMPLETED,
                    owner
                );
            }
        }

        return buildSession(record);
    }

    @Override
    public String getSessionJobId(String sessionId, String owner) {
        PlaybackSessionRecord record = sessions.get(sessionId);
        if (record == null || !isAccessible(record, owner)) {
            return null;
        }
        return record.jobId;
    }

    @Override
    public void closeSession(String sessionId, String owner) {
        PlaybackSessionRecord record = sessions.get(sessionId);
        if (record == null) {
            return;
        }
        if (!isAccessible(record, owner)) {
            sneakyThrow(nyxException(ErrorCode.PATH_NOT_ALLOWED, "Not authorized to close this playback session"));
        }
        if (record.closed) {
            return;
        }
        record.closed = true;
        if (record.readyAt == null && record.directPath != null) {
            record.readyAt = record.createdAt;
            record.lifecycleProgressPercent = 100.0;
        }
        terminateSession(
            record,
            record.endedAt != null ? record.endedAt : nowIso(),
            PlaybackLifecycleEndReason.CLIENT_REQUESTED,
            MediaSessionPlaybackEvent.STOPPED,
            owner
        );
    }

    @Override
    public String getHlsManifest(String sessionId, String owner) {
        PlaybackSessionRecord record = sessions.get(sessionId);
        if (record == null || !isAccessible(record, owner) || record.closed || record.jobId == null) {
            return null;
        }
        return transcodeService.getManifestM3u8(record.jobId);
    }

    @Override
    public String getDashManifest(String sessionId, String owner) {
        PlaybackSessionRecord record = sessions.get(sessionId);
        if (record == null || !isAccessible(record, owner) || record.closed || record.jobId == null) {
            return null;
        }
        return transcodeService.getManifestMpd(record.jobId);
    }

    @Override
    public Path getDirectContentPath(String sessionId, String owner) {
        PlaybackSessionRecord record = sessions.get(sessionId);
        if (record == null || !isAccessible(record, owner) || record.closed) {
            return null;
        }
        return record.directPath;
    }

    @Override
    public void shutdown() {
        int sessionCount = sessions.size();
        sessions.values().forEach(record -> {
            if (record.eventSubscription != null) {
                record.eventSubscription.cancel();
            }
            if (record.cleanupTask != null) {
                record.cleanupTask.cancel(true);
            }
        });
        sessions.clear();
        if (ownsCleanupScheduler) {
            scheduler.shutdownNow();
        }
        LOGGER.info("Playback session service shutdown complete; cleared {} session record(s)", sessionCount);
    }

    private PlaybackSession buildSession(PlaybackSessionRecord record) {
        PlaybackSessionResolution resolution = resolveSession(record);
        MediaSessionTelemetry telemetry = new MediaSessionTelemetry(
            record.objectId,
            record.mediaKind,
            record.lastEvent,
            record.lastEventAt,
            record.positionMillis,
            record.durationMillis,
            record.playbackProgressPercent,
            record.clientName,
            record.deviceName,
            record.playbackContext
        );
        return new PlaybackSession(
            record.sessionId,
            record.objectId,
            record.mediaKind,
            resolution.state,
            record.decision,
            resolution.artifacts,
            telemetry,
            resolution.failureCode,
            resolution.failureMessage,
            record.createdAt,
            resolution.lifecycle
        );
    }

    private PlaybackSessionArtifacts artifactsFor(PlaybackSessionRecord record) {
        return switch (record.preferredProtocol) {
            case HLS -> new PlaybackSessionArtifacts(
                StreamingProtocol.HLS,
                "/api/v1/playback/sessions/" + record.sessionId + "/master.m3u8",
                null,
                "/api/v1/playback/sessions/" + record.sessionId + "/master.m3u8",
                null
            );
            case DASH -> new PlaybackSessionArtifacts(
                StreamingProtocol.DASH,
                "/api/v1/playback/sessions/" + record.sessionId + "/manifest.mpd",
                null,
                null,
                "/api/v1/playback/sessions/" + record.sessionId + "/manifest.mpd"
            );
            case FILE -> new PlaybackSessionArtifacts(
                StreamingProtocol.FILE,
                "/api/v1/playback/sessions/" + record.sessionId + "/content",
                "/api/v1/playback/sessions/" + record.sessionId + "/content",
                null,
                null
            );
        };
    }

    private boolean isAccessible(PlaybackSessionRecord record, String owner) {
        if (record.owner == null || owner == null) {
            return true;
        }
        return Objects.equals(record.owner, owner);
    }

    private PlaybackSessionResolution resolveSession(PlaybackSessionRecord record) {
        if (record.closed) {
            return resolution(
                record,
                PlaybackSessionState.CLOSED,
                new PlaybackSessionLifecycle(
                    PlaybackLifecyclePhase.STOPPED,
                    record.createdAt,
                    record.readyAt,
                    record.endedAt != null ? record.endedAt : record.createdAt,
                    record.lifecycleProgressPercent != null
                        ? record.lifecycleProgressPercent
                        : record.readyAt != null ? 100.0 : null,
                    false,
                    record.endReason != null
                        ? record.endReason
                        : PlaybackLifecycleEndReason.CLIENT_REQUESTED
                ),
                null,
                null,
                null
            );
        }

        if (record.directPath != null) {
            return resolution(
                record,
                PlaybackSessionState.READY,
                new PlaybackSessionLifecycle(
                    PlaybackLifecyclePhase.READY,
                    record.createdAt,
                    record.readyAt != null ? record.readyAt : record.createdAt,
                    null,
                    100.0,
                    true,
                    null
                ),
                artifactsFor(record),
                null,
                null
            );
        }

        String jobId = record.jobId;
        if (jobId == null) {
            return failedResolution(
                record,
                PlaybackLifecyclePhase.FAILED,
                "SESSION_INVALID",
                "Playback session has no backing transcode job",
                PlaybackLifecycleEndReason.SESSION_INVALID,
                null
            );
        }

        TranscodeJob job = transcodeService.getJob(jobId);
        if (job == null) {
            return failedResolution(
                record,
                PlaybackLifecyclePhase.ABANDONED,
                "JOB_NOT_FOUND",
                "Backing transcode job not found",
                PlaybackLifecycleEndReason.BACKING_JOB_MISSING,
                null
            );
        }

        PlaybackSessionArtifacts artifacts = artifactsFor(record);
        boolean ready = switch (record.preferredProtocol) {
            case HLS -> transcodeService.getManifestM3u8(jobId) != null;
            case DASH -> transcodeService.getManifestMpd(jobId) != null;
            case FILE -> true;
        };

        if (job.getStatus() == JobStatus.COMPLETED || ready) {
            record.readyAt = record.readyAt != null ? record.readyAt : nowIso();
            record.lifecycleProgressPercent = 100.0;
            return resolution(
                record,
                PlaybackSessionState.READY,
                new PlaybackSessionLifecycle(
                    PlaybackLifecyclePhase.READY,
                    record.createdAt,
                    record.readyAt,
                    null,
                    100.0,
                    true,
                    null
                ),
                artifacts,
                null,
                null
            );
        }

        if (job.getStatus() == JobStatus.CANCELLED) {
            return failedResolution(
                record,
                PlaybackLifecyclePhase.ABANDONED,
                job.getStatus().name(),
                "Playback session was abandoned after the backing transcode job was cancelled",
                PlaybackLifecycleEndReason.BACKING_JOB_CANCELLED,
                artifacts
            );
        }

        if (job.getStatus() == JobStatus.FAILED) {
            String failureMessage = transcodeService.getLogs(jobId);
            if (failureMessage.isBlank()) {
                failureMessage = "Playback session failed while preparing stream output";
            }
            return failedResolution(
                record,
                PlaybackLifecyclePhase.FAILED,
                job.getStatus().name(),
                failureMessage,
                PlaybackLifecycleEndReason.BACKING_JOB_FAILED,
                artifacts
            );
        }

        return resolution(
            record,
            PlaybackSessionState.PENDING,
            new PlaybackSessionLifecycle(
                PlaybackLifecyclePhase.STARTING,
                record.createdAt,
                null,
                null,
                record.lifecycleProgressPercent != null ? record.lifecycleProgressPercent : 0.0,
                true,
                null
            ),
            artifacts,
            null,
            null
        );
    }

    private PlaybackSessionResolution failedResolution(
        PlaybackSessionRecord record,
        PlaybackLifecyclePhase phase,
        String failureCode,
        String failureMessage,
        PlaybackLifecycleEndReason endReason,
        PlaybackSessionArtifacts artifacts
    ) {
        record.endedAt = record.endedAt != null ? record.endedAt : nowIso();
        record.endReason = record.endReason != null ? record.endReason : endReason;
        if (record.eventSubscription != null) {
            record.eventSubscription.cancel();
            record.eventSubscription = null;
        }
        return resolution(
            record,
            PlaybackSessionState.FAILED,
            new PlaybackSessionLifecycle(
                phase,
                record.createdAt,
                record.readyAt,
                record.endedAt,
                record.lifecycleProgressPercent,
                false,
                record.endReason
            ),
            artifacts,
            failureCode,
            failureMessage
        );
    }

    private void observeJobLifecycle(PlaybackSessionRecord record) {
        if (record.jobId == null) {
            return;
        }
        Flow.Publisher<JobEvent> eventFlow = transcodeService.eventFlow(record.jobId);
        if (eventFlow == null) {
            return;
        }
        eventFlow.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                if (record.closed) {
                    subscription.cancel();
                    return;
                }
                record.eventSubscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(JobEvent event) {
                if (event instanceof JobEvent.Progress progress) {
                    record.lifecycleProgressPercent = Math.clamp(progress.getPercent(), 0.0, 100.0);
                    return;
                }
                if (event instanceof JobEvent.Complete) {
                    record.lifecycleProgressPercent = 100.0;
                    record.readyAt = record.readyAt != null ? record.readyAt : nowIso();
                    return;
                }
                if (event instanceof JobEvent.Error error) {
                    if ("CANCELLED".equals(error.getCode()) && !record.closed) {
                        record.endedAt = record.endedAt != null ? record.endedAt : nowIso();
                        record.endReason = record.endReason != null
                            ? record.endReason
                            : PlaybackLifecycleEndReason.BACKING_JOB_CANCELLED;
                    }
                }
            }

            @Override
            public void onError(Throwable throwable) {
                LOGGER.debug(
                    "Playback session job event stream failed for {}: {}",
                    record.jobId,
                    throwable.getMessage()
                );
                record.eventSubscription = null;
            }

            @Override
            public void onComplete() {
                record.eventSubscription = null;
            }
        });
    }

    private String nowIso() {
        return Instant.now(clock).toString();
    }

    private void terminateSession(
        PlaybackSessionRecord record,
        String endedAt,
        PlaybackLifecycleEndReason endReason,
        MediaSessionPlaybackEvent lastEvent,
        String owner
    ) {
        record.closed = true;
        record.endedAt = endedAt;
        record.endReason = endReason;
        record.lastEvent = lastEvent;
        record.lastEventAt = endedAt;
        if (record.readyAt == null && record.directPath != null) {
            record.readyAt = record.createdAt;
            record.lifecycleProgressPercent = 100.0;
        }
        if (record.eventSubscription != null) {
            record.eventSubscription.cancel();
            record.eventSubscription = null;
        }
        logLifecycleTransition(record, PlaybackLifecyclePhase.STOPPED, endReason);
        scheduleSessionCleanup(record);
        if (record.jobId == null) {
            return;
        }
        TranscodeJob job = transcodeService.getJob(record.jobId);
        if (job == null) {
            return;
        }
        if (!Set.of(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED).contains(job.getStatus())) {
            transcodeService.cancel(record.jobId, owner);
        }
    }

    private void validateReportIdentity(PlaybackSessionRecord record, MediaSessionPlaybackReport report) {
        if (record.objectId != null && report.objectId() != null && !record.objectId.equals(report.objectId())) {
            sneakyThrow(nyxException(
                ErrorCode.INVALID_REQUEST,
                "Playback session report objectId does not match session identity"
            ));
        }
        if (report.mediaKind() != null && report.mediaKind() != record.mediaKind) {
            sneakyThrow(nyxException(
                ErrorCode.INVALID_REQUEST,
                "Playback session report mediaKind does not match session identity"
            ));
        }
    }

    private Double resolvePlaybackProgressPercent(
        Long positionMillis,
        Long durationMillis,
        MediaSessionPlaybackEvent event,
        Double fallback
    ) {
        if (event == MediaSessionPlaybackEvent.COMPLETED) {
            return 100.0;
        }
        Double initial = initialProgressPercent(positionMillis, durationMillis);
        return initial != null ? initial : fallback != null ? fallback : 0.0;
    }

    private PlaybackSessionResolution resolution(
        PlaybackSessionRecord record,
        PlaybackSessionState state,
        PlaybackSessionLifecycle lifecycle,
        PlaybackSessionArtifacts artifacts,
        String failureCode,
        String failureMessage
    ) {
        logLifecycleTransition(record, lifecycle.phase(), lifecycle.endReason());
        if (Set.of(
            PlaybackLifecyclePhase.STOPPED,
            PlaybackLifecyclePhase.ABANDONED,
            PlaybackLifecyclePhase.FAILED
        ).contains(lifecycle.phase())) {
            scheduleSessionCleanup(record);
        }
        return new PlaybackSessionResolution(state, lifecycle, artifacts, failureCode, failureMessage);
    }

    private void scheduleSessionCleanup(PlaybackSessionRecord record) {
        if (record.cleanupTask != null) {
            return;
        }
        record.cleanupTask = scheduler.schedule(() -> {
            boolean removed = sessions.remove(record.sessionId, record);
            if (removed) {
                LOGGER.info(
                    "Cleaned up playback session {} owner={} mode={} phase={} retentionMs={}",
                    record.sessionId,
                    record.owner != null ? record.owner : "anonymous",
                    record.decision.mode(),
                    record.lastPhase != null ? record.lastPhase : "unknown",
                    terminalSessionRetentionMs
                );
            }
        }, terminalSessionRetentionMs, TimeUnit.MILLISECONDS);
    }

    private void logLifecycleTransition(
        PlaybackSessionRecord record,
        PlaybackLifecyclePhase phase,
        PlaybackLifecycleEndReason endReason
    ) {
        PlaybackLifecyclePhase previousPhase = record.lastPhase;
        if (previousPhase == phase) {
            return;
        }
        record.lastPhase = phase;
        LOGGER.info(
            "Playback session {} owner={} mode={} lifecycle {} -> {} jobId={} endReason={}",
            record.sessionId,
            record.owner != null ? record.owner : "anonymous",
            record.decision.mode(),
            previousPhase != null ? previousPhase : "NEW",
            phase,
            record.jobId != null ? record.jobId : "direct",
            endReason != null ? endReason : "none"
        );
    }

    private void logDecision(String sessionId, String owner, PlaybackDecision decision) {
        LOGGER.info(
            "Opening playback session {} owner={} mode={} protocol={} container={} reasons={}",
            sessionId,
            owner != null ? owner : "anonymous",
            decision.mode(),
            decision.stream().protocol(),
            decision.stream().container(),
            decision.reasons()
        );
    }

    private TranscodeJob callTranscodeSubmit(PlaybackRequest request, PlaybackDecision decision, String owner) {
        return transcodeService.submit(request, decision, null, owner);
    }

    private NyxException nyxException(ErrorCode errorCode, String message) {
        return new NyxException(errorCode, message, Map.of(), null);
    }

    private static Double initialProgressPercent(Long positionMillis, Long durationMillis) {
        if (positionMillis == null || durationMillis == null || durationMillis <= 0L) {
            return null;
        }
        return Math.clamp((positionMillis.doubleValue() / durationMillis.doubleValue()) * 100.0, 0.0, 100.0);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private static final class PlaybackSessionRecord {
        private final String sessionId;
        private final String owner;
        private final PlaybackRequest request;
        private final String createdAt;
        private final StreamingProtocol preferredProtocol;
        private final PlaybackDecision decision;
        private final Path directPath;
        private final String jobId;
        private volatile String objectId;
        private volatile MediaKind mediaKind;
        private volatile MediaSessionPlaybackEvent lastEvent;
        private volatile String lastEventAt;
        private volatile Long positionMillis;
        private volatile Long durationMillis;
        private volatile Double playbackProgressPercent;
        private volatile String clientName;
        private volatile String deviceName;
        private volatile String playbackContext;
        private volatile boolean closed;
        private volatile String readyAt;
        private volatile String endedAt;
        private volatile PlaybackLifecycleEndReason endReason;
        private volatile Double lifecycleProgressPercent;
        private volatile Flow.Subscription eventSubscription;
        private volatile ScheduledFuture<?> cleanupTask;
        private volatile PlaybackLifecyclePhase lastPhase;

        private PlaybackSessionRecord(
            String sessionId,
            String owner,
            PlaybackRequest request,
            String createdAt,
            StreamingProtocol preferredProtocol,
            PlaybackDecision decision,
            Path directPath,
            String jobId
        ) {
            this.sessionId = sessionId;
            this.owner = owner;
            this.request = request;
            this.createdAt = createdAt;
            this.preferredProtocol = preferredProtocol;
            this.decision = decision;
            this.directPath = directPath;
            this.jobId = jobId;
            this.objectId = request.source().objectId();
            this.mediaKind = request.source().mediaKind() != null ? request.source().mediaKind() : MediaKind.VIDEO;
            this.positionMillis = request.startPositionMillis() > 0 ? request.startPositionMillis() : null;
            this.durationMillis = request.source().characteristics() != null
                ? request.source().characteristics().durationMillis()
                : null;
        }
    }

    private record PlaybackSessionResolution(
        PlaybackSessionState state,
        PlaybackSessionLifecycle lifecycle,
        PlaybackSessionArtifacts artifacts,
        String failureCode,
        String failureMessage
    ) {}
}
