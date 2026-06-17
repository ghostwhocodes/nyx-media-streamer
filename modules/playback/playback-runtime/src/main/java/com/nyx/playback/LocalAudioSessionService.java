package com.nyx.playback;

import com.nyx.common.ErrorCode;
import com.nyx.common.ManagedService;
import com.nyx.common.NyxException;
import com.nyx.media.contracts.MediaKind;
import com.nyx.playback.contracts.AudioNegotiationDecision;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.AudioNegotiationService;
import com.nyx.playback.contracts.AudioSession;
import com.nyx.playback.contracts.AudioSessionArtifacts;
import com.nyx.playback.contracts.AudioSessionService;
import com.nyx.playback.contracts.MediaSessionPlaybackEvent;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.nyx.playback.contracts.MediaSessionTelemetry;
import com.nyx.playback.contracts.PlaybackLifecycleEndReason;
import com.nyx.playback.contracts.PlaybackLifecyclePhase;
import com.nyx.playback.contracts.PlaybackSessionLifecycle;
import com.nyx.playback.contracts.PlaybackSessionState;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalAudioSessionService implements AudioSessionService, ManagedService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalAudioSessionService.class);
    private static final long DEFAULT_TERMINAL_SESSION_RETENTION_MS = 5 * 60_000L;

    private final AudioNegotiationService audioNegotiationService;
    private final long terminalSessionRetentionMs;
    private final Clock clock;
    private final ConcurrentHashMap<String, AudioSessionRecord> sessions = new ConcurrentHashMap<>();
    private final boolean ownsCleanupScheduler;
    private final ScheduledExecutorService scheduler;

    public LocalAudioSessionService(AudioNegotiationService audioNegotiationService) {
        this(audioNegotiationService, null, DEFAULT_TERMINAL_SESSION_RETENTION_MS, Clock.systemUTC());
    }

    public LocalAudioSessionService(
        AudioNegotiationService audioNegotiationService,
        ScheduledExecutorService cleanupScheduler
    ) {
        this(audioNegotiationService, cleanupScheduler, DEFAULT_TERMINAL_SESSION_RETENTION_MS, Clock.systemUTC());
    }

    public LocalAudioSessionService(
        AudioNegotiationService audioNegotiationService,
        ScheduledExecutorService cleanupScheduler,
        long terminalSessionRetentionMs
    ) {
        this(audioNegotiationService, cleanupScheduler, terminalSessionRetentionMs, Clock.systemUTC());
    }

    public LocalAudioSessionService(
        AudioNegotiationService audioNegotiationService,
        ScheduledExecutorService cleanupScheduler,
        long terminalSessionRetentionMs,
        Clock clock
    ) {
        this.audioNegotiationService = audioNegotiationService;
        this.terminalSessionRetentionMs = terminalSessionRetentionMs;
        this.clock = clock;
        this.ownsCleanupScheduler = cleanupScheduler == null;
        this.scheduler = cleanupScheduler != null
            ? cleanupScheduler
            : Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "nyx-audio-session-cleanup");
                thread.setDaemon(true);
                return thread;
            });
    }

    @Override
    public AudioSession openSession(AudioNegotiationRequest request, String owner) {
        AudioNegotiationDecision decision = audioNegotiationService.decide(request);
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String createdAt = Instant.now(clock).toString();
        AudioSessionRecord record = new AudioSessionRecord(sessionId, owner, request, createdAt, createdAt, decision);
        record.progressPercent = initialProgressPercent(record.positionMillis, record.durationMillis);
        if (record.progressPercent == null) {
            record.progressPercent = 0.0;
        }
        sessions.put(sessionId, record);
        logLifecycleTransition(record, PlaybackLifecyclePhase.READY, null);
        return buildSession(record);
    }

    @Override
    public AudioSession getSession(String sessionId, String owner) {
        AudioSessionRecord record = sessions.get(sessionId);
        if (record == null || !isAccessible(record, owner)) {
            return null;
        }
        return buildSession(record);
    }

    @Override
    public AudioNegotiationRequest getSessionRequest(String sessionId, String owner) {
        AudioSessionRecord record = sessions.get(sessionId);
        if (record == null || !isAccessible(record, owner) || record.closed) {
            return null;
        }
        return record.request;
    }

    @Override
    public AudioSession reportPlayback(String sessionId, MediaSessionPlaybackReport report, String owner) {
        AudioSessionRecord record = sessions.get(sessionId);
        if (record == null) {
            sneakyThrow(new NyxException(ErrorCode.JOB_NOT_FOUND, "Audio session not found: " + sessionId, Map.of(), null));
        }
        if (!isAccessible(record, owner)) {
            sneakyThrow(new NyxException(
                ErrorCode.PATH_NOT_ALLOWED,
                "Not authorized to report playback for this audio session",
                Map.of(),
                null
            ));
        }
        if (record.closed) {
            sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Audio session is no longer active: " + sessionId,
                Map.of(),
                null
            ));
        }
        validateReportIdentity(record, report);

        if (report.positionMillis() != null) {
            if (report.positionMillis() < 0) {
                sneakyThrow(new NyxException(
                    ErrorCode.INVALID_REQUEST,
                    "Audio session positionMillis must be non-negative",
                    Map.of(),
                    null
                ));
            }
            record.positionMillis = report.positionMillis();
        }
        if (report.durationMillis() != null) {
            if (report.durationMillis() <= 0) {
                sneakyThrow(new NyxException(
                    ErrorCode.INVALID_REQUEST,
                    "Audio session durationMillis must be positive when provided",
                    Map.of(),
                    null
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
        record.progressPercent = resolveProgressPercent(
            record.positionMillis,
            record.durationMillis,
            report.event(),
            record.progressPercent
        );

        switch (report.event()) {
            case STARTED, HEARTBEAT -> {
            }
            case STOPPED -> {
                record.closed = true;
                record.endedAt = occurredAt;
                record.endReason = PlaybackLifecycleEndReason.CLIENT_REQUESTED;
                logLifecycleTransition(record, PlaybackLifecyclePhase.STOPPED, PlaybackLifecycleEndReason.CLIENT_REQUESTED);
                scheduleSessionCleanup(record);
            }
            case COMPLETED -> {
                record.closed = true;
                record.endedAt = occurredAt;
                record.endReason = PlaybackLifecycleEndReason.PLAYBACK_COMPLETED;
                record.progressPercent = 100.0;
                if (record.durationMillis != null) {
                    record.positionMillis = record.durationMillis;
                }
                logLifecycleTransition(record, PlaybackLifecyclePhase.STOPPED, PlaybackLifecycleEndReason.PLAYBACK_COMPLETED);
                scheduleSessionCleanup(record);
            }
        }

        return buildSession(record);
    }

    @Override
    public void closeSession(String sessionId, String owner) {
        AudioSessionRecord record = sessions.get(sessionId);
        if (record == null) {
            return;
        }
        if (!isAccessible(record, owner)) {
            sneakyThrow(new NyxException(
                ErrorCode.PATH_NOT_ALLOWED,
                "Not authorized to close this audio session",
                Map.of(),
                null
            ));
        }
        if (record.closed) {
            return;
        }
        record.closed = true;
        record.endedAt = record.endedAt != null ? record.endedAt : nowIso();
        record.endReason = PlaybackLifecycleEndReason.CLIENT_REQUESTED;
        record.lastEvent = MediaSessionPlaybackEvent.STOPPED;
        record.lastEventAt = record.endedAt;
        logLifecycleTransition(record, PlaybackLifecyclePhase.STOPPED, PlaybackLifecycleEndReason.CLIENT_REQUESTED);
        scheduleSessionCleanup(record);
    }

    @Override
    public Path getSourcePath(String sessionId, String owner) {
        AudioSessionRecord record = sessions.get(sessionId);
        if (record == null || !isAccessible(record, owner) || record.closed) {
            return null;
        }
        return Path.of(record.request.source().path());
    }

    @Override
    public void shutdown() {
        int sessionCount = sessions.size();
        sessions.values().forEach(record -> {
            if (record.cleanupTask != null) {
                record.cleanupTask.cancel(true);
            }
        });
        sessions.clear();
        if (ownsCleanupScheduler) {
            scheduler.shutdownNow();
        }
        LOGGER.info("Audio session service shutdown complete; cleared {} session record(s)", sessionCount);
    }

    private AudioSession buildSession(AudioSessionRecord record) {
        AudioSessionArtifacts artifacts = record.closed ? null : artifactsFor(record);
        MediaSessionTelemetry telemetry = new MediaSessionTelemetry(
            record.objectId,
            record.mediaKind,
            record.lastEvent,
            record.lastEventAt,
            record.positionMillis,
            record.durationMillis,
            record.progressPercent,
            record.clientName,
            record.deviceName,
            record.playbackContext
        );
        PlaybackSessionLifecycle lifecycle;
        if (record.closed) {
            lifecycle = new PlaybackSessionLifecycle(
                PlaybackLifecyclePhase.STOPPED,
                record.createdAt,
                record.readyAt,
                record.endedAt,
                record.progressPercent,
                false,
                record.endReason
            );
        } else {
            lifecycle = new PlaybackSessionLifecycle(
                PlaybackLifecyclePhase.READY,
                record.createdAt,
                record.readyAt,
                null,
                record.progressPercent,
                true,
                null
            );
        }
        return new AudioSession(
            record.sessionId,
            record.objectId,
            record.mediaKind,
            record.closed ? PlaybackSessionState.CLOSED : PlaybackSessionState.READY,
            record.request,
            record.decision,
            artifacts,
            telemetry,
            null,
            null,
            record.createdAt,
            lifecycle
        );
    }

    private AudioSessionArtifacts artifactsFor(AudioSessionRecord record) {
        String contentUrl = "/api/v1/audio/sessions/" + record.sessionId + "/content";
        return new AudioSessionArtifacts(contentUrl, contentUrl);
    }

    private boolean isAccessible(AudioSessionRecord record, String owner) {
        return record.owner == null || owner == null || record.owner.equals(owner);
    }

    private void validateReportIdentity(AudioSessionRecord record, MediaSessionPlaybackReport report) {
        if (record.objectId != null && report.objectId() != null && !record.objectId.equals(report.objectId())) {
            sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Audio session report objectId does not match session identity",
                Map.of(),
                null
            ));
        }
        if (report.mediaKind() != null && report.mediaKind() != record.mediaKind) {
            sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Audio session report mediaKind does not match session identity",
                Map.of(),
                null
            ));
        }
    }

    private void scheduleSessionCleanup(AudioSessionRecord record) {
        if (record.cleanupTask != null) {
            return;
        }
        record.cleanupTask = scheduler.schedule(() -> {
            boolean removed = sessions.remove(record.sessionId, record);
            if (removed) {
                LOGGER.info(
                    "Cleaned up audio session {} owner={} mode={} retentionMs={}",
                    record.sessionId,
                    record.owner != null ? record.owner : "anonymous",
                    record.decision.mode(),
                    terminalSessionRetentionMs
                );
            }
        }, terminalSessionRetentionMs, TimeUnit.MILLISECONDS);
    }

    private void logLifecycleTransition(
        AudioSessionRecord record,
        PlaybackLifecyclePhase phase,
        PlaybackLifecycleEndReason endReason
    ) {
        PlaybackLifecyclePhase previousPhase = record.lastPhase;
        if (previousPhase == phase) {
            return;
        }
        record.lastPhase = phase;
        LOGGER.info(
            "Audio session {} owner={} mode={} lifecycle {} -> {} endReason={}",
            record.sessionId,
            record.owner != null ? record.owner : "anonymous",
            record.decision.mode(),
            previousPhase != null ? previousPhase : "NEW",
            phase,
            endReason != null ? endReason : "none"
        );
    }

    private String nowIso() {
        return Instant.now(clock).toString();
    }

    private double resolveProgressPercent(
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

    private static Double initialProgressPercent(Long positionMillis, Long durationMillis) {
        if (positionMillis == null || durationMillis == null || durationMillis <= 0L) {
            return null;
        }
        return Math.clamp((positionMillis.doubleValue() / durationMillis.doubleValue()) * 100.0, 0.0, 100.0);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private static final class AudioSessionRecord {
        private final String sessionId;
        private final String owner;
        private final AudioNegotiationRequest request;
        private final String createdAt;
        private final String readyAt;
        private final AudioNegotiationDecision decision;
        private volatile String objectId;
        private volatile MediaKind mediaKind;
        private volatile MediaSessionPlaybackEvent lastEvent;
        private volatile String lastEventAt;
        private volatile Long positionMillis;
        private volatile Long durationMillis;
        private volatile Double progressPercent;
        private volatile String clientName;
        private volatile String deviceName;
        private volatile String playbackContext;
        private volatile boolean closed;
        private volatile String endedAt;
        private volatile PlaybackLifecycleEndReason endReason;
        private volatile ScheduledFuture<?> cleanupTask;
        private volatile PlaybackLifecyclePhase lastPhase;

        private AudioSessionRecord(
            String sessionId,
            String owner,
            AudioNegotiationRequest request,
            String createdAt,
            String readyAt,
            AudioNegotiationDecision decision
        ) {
            this.sessionId = sessionId;
            this.owner = owner;
            this.request = request;
            this.createdAt = createdAt;
            this.readyAt = readyAt;
            this.decision = decision;
            this.objectId = request.source().objectId();
            this.mediaKind = request.source().mediaKind() != null ? request.source().mediaKind() : MediaKind.AUDIO;
            this.positionMillis = request.startPositionMillis() > 0 ? request.startPositionMillis() : null;
            this.durationMillis = request.source().characteristics() != null
                ? request.source().characteristics().durationMillis()
                : null;
        }
    }
}
