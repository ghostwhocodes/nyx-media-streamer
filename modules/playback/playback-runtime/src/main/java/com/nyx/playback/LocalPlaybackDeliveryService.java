package com.nyx.playback;

import com.nyx.common.ManagedService;
import com.nyx.playback.contracts.PlaybackDeliveryFailed;
import com.nyx.playback.contracts.PlaybackDeliveryLeasePolicy;
import com.nyx.playback.contracts.PlaybackDeliveryOutcome;
import com.nyx.playback.contracts.PlaybackDeliveryPending;
import com.nyx.playback.contracts.PlaybackDeliveryReadyFile;
import com.nyx.playback.contracts.PlaybackDeliveryReadyManifest;
import com.nyx.playback.contracts.PlaybackDeliveryReadiness;
import com.nyx.playback.contracts.PlaybackDeliveryRequest;
import com.nyx.playback.contracts.PlaybackDeliveryRequirement;
import com.nyx.playback.contracts.PlaybackDeliveryService;
import com.nyx.playback.contracts.PlaybackDeliverySessionRequest;
import com.nyx.playback.contracts.PlaybackDeliveryStartupPolicy;
import com.nyx.playback.contracts.PlaybackDeliveryTerminated;
import com.nyx.playback.contracts.PlaybackDeliveryTimeoutAction;
import com.nyx.playback.contracts.PlaybackDeliveryUnavailable;
import com.nyx.playback.contracts.PlaybackLifecyclePhase;
import com.nyx.playback.contracts.PlaybackSession;
import com.nyx.playback.contracts.PlaybackSessionArtifacts;
import com.nyx.playback.contracts.PlaybackSessionLifecycle;
import com.nyx.playback.contracts.PlaybackSessionService;
import com.nyx.playback.contracts.PlaybackSessionState;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class LocalPlaybackDeliveryService implements PlaybackDeliveryService, ManagedService {
    private static final String FAILURE_JOB_NOT_FOUND = "JOB_NOT_FOUND";
    private static final String FAILURE_TRANSCODE_FAILED = "TRANSCODE_FAILED";

    private final PlaybackSessionService playbackSessionService;
    private final ScheduledExecutorService cleanupScheduler;
    private final boolean ownsCleanupScheduler;
    private final Clock clock;
    private final ConcurrentMap<String, String> sessionsByLeaseKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> leaseKeysBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DeliveryLease> leases = new ConcurrentHashMap<>();

    public LocalPlaybackDeliveryService(PlaybackSessionService playbackSessionService) {
        this(playbackSessionService, null, Clock.systemUTC());
    }

    public LocalPlaybackDeliveryService(
        PlaybackSessionService playbackSessionService,
        ScheduledExecutorService cleanupScheduler
    ) {
        this(playbackSessionService, cleanupScheduler, Clock.systemUTC());
    }

    public LocalPlaybackDeliveryService(
        PlaybackSessionService playbackSessionService,
        ScheduledExecutorService cleanupScheduler,
        Clock clock
    ) {
        this.playbackSessionService = Objects.requireNonNull(playbackSessionService, "playbackSessionService");
        this.ownsCleanupScheduler = cleanupScheduler == null;
        this.cleanupScheduler = cleanupScheduler != null
            ? cleanupScheduler
            : Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "nyx-playback-delivery-cleanup");
                thread.setDaemon(true);
                return thread;
            });
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public PlaybackDeliveryOutcome open(PlaybackDeliveryRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.playbackRequest(), "playbackRequest");
        PlaybackDeliveryLeasePolicy leasePolicy = request.leasePolicy();
        PlaybackSession session = cachedLeasedSession(leasePolicy, request.owner());
        if (session == null) {
            PlaybackSession openedSession = playbackSessionService.openSession(request.playbackRequest(), request.owner());
            session = retainOpenedSession(openedSession, leasePolicy, request.owner());
        }
        PlaybackDeliveryOutcome outcome;
        try {
            outcome = awaitReadiness(
                session,
                request.owner(),
                request.readiness(),
                request.startupPolicy(),
                leasePolicy,
                true
            );
        } catch (RuntimeException | Error exception) {
            cleanupStartupException(session, request.owner(), leasePolicy);
            throw exception;
        }
        updateLeaseState(outcome, leasePolicy, request.owner());
        return outcome;
    }

    @Override
    public PlaybackDeliveryOutcome observe(PlaybackDeliverySessionRequest request) {
        Objects.requireNonNull(request, "request");
        PlaybackSession session = playbackSessionService.getSession(request.sessionId(), request.owner());
        if (session == null) {
            clearDeliveryStateForOwner(request.sessionId(), request.owner());
            return new PlaybackDeliveryTerminated(
                null,
                PlaybackLifecyclePhase.STOPPED,
                "Playback session not found: " + request.sessionId()
            );
        }
        PlaybackDeliveryOutcome outcome = awaitReadiness(
            session,
            request.owner(),
            request.readiness(),
            request.startupPolicy(),
            request.leasePolicy(),
            false
        );
        updateLeaseState(outcome, request.leasePolicy(), request.owner());
        return outcome;
    }

    @Override
    public void close(String sessionId, String owner) {
        playbackSessionService.closeSession(sessionId, owner);
        clearDeliveryStateForOwner(sessionId, owner);
    }

    @Override
    public void shutdown() {
        leases.values().forEach(LocalPlaybackDeliveryService::cancelCleanup);
        leases.clear();
        sessionsByLeaseKey.clear();
        leaseKeysBySession.clear();
        if (ownsCleanupScheduler) {
            cleanupScheduler.shutdownNow();
        }
    }

    private PlaybackSession cachedLeasedSession(PlaybackDeliveryLeasePolicy leasePolicy, String owner) {
        if (!leasePolicy.enabled()) {
            return null;
        }
        String sessionId = sessionsByLeaseKey.get(leasePolicy.leaseKey());
        if (sessionId == null) {
            return null;
        }
        PlaybackSession session = playbackSessionService.getSession(sessionId, owner);
        if (session == null) {
            clearLeaseMapping(leasePolicy.leaseKey(), sessionId);
            return null;
        }

        return switch (lifecyclePhase(session)) {
            case STARTING -> {
                if (leasePolicy.reuseStartingSessions()) {
                    leaseKeysBySession.putIfAbsent(sessionId, leasePolicy.leaseKey());
                    yield session;
                }
                clearLeaseMapping(leasePolicy.leaseKey(), sessionId);
                yield null;
            }
            case READY -> {
                if (isReadyFileSession(session) && leasePolicy.reuseReadyFileSessions()) {
                    leaseKeysBySession.putIfAbsent(sessionId, leasePolicy.leaseKey());
                } else {
                    clearLeaseMapping(leasePolicy.leaseKey(), sessionId);
                }
                yield session;
            }
            case FAILED -> {
                clearLeaseMapping(leasePolicy.leaseKey(), sessionId);
                yield session;
            }
            case STOPPED, ABANDONED -> {
                clearLeaseMapping(leasePolicy.leaseKey(), sessionId);
                yield null;
            }
        };
    }

    private PlaybackSession retainOpenedSession(
        PlaybackSession openedSession,
        PlaybackDeliveryLeasePolicy leasePolicy,
        String owner
    ) {
        if (!leasePolicy.enabled() || !isReusableForLease(openedSession, leasePolicy)) {
            return openedSession;
        }

        String existingSessionId = sessionsByLeaseKey.putIfAbsent(leasePolicy.leaseKey(), openedSession.sessionId());
        if (existingSessionId == null || existingSessionId.equals(openedSession.sessionId())) {
            leaseKeysBySession.put(openedSession.sessionId(), leasePolicy.leaseKey());
            return openedSession;
        }

        PlaybackSession existingSession = playbackSessionService.getSession(existingSessionId, owner);
        if (isReusableForLease(existingSession, leasePolicy)) {
            closeDuplicateSession(openedSession.sessionId(), owner, leasePolicy);
            leaseKeysBySession.putIfAbsent(existingSessionId, leasePolicy.leaseKey());
            return existingSession;
        }

        clearLeaseMapping(leasePolicy.leaseKey(), existingSessionId);
        String replacementSessionId = sessionsByLeaseKey.putIfAbsent(
            leasePolicy.leaseKey(),
            openedSession.sessionId()
        );
        if (replacementSessionId == null || replacementSessionId.equals(openedSession.sessionId())) {
            leaseKeysBySession.put(openedSession.sessionId(), leasePolicy.leaseKey());
            return openedSession;
        }

        PlaybackSession replacementSession = playbackSessionService.getSession(replacementSessionId, owner);
        if (isReusableForLease(replacementSession, leasePolicy)) {
            closeDuplicateSession(openedSession.sessionId(), owner, leasePolicy);
            leaseKeysBySession.putIfAbsent(replacementSessionId, leasePolicy.leaseKey());
            return replacementSession;
        }
        return openedSession;
    }

    private PlaybackDeliveryOutcome awaitReadiness(
        PlaybackSession session,
        String owner,
        PlaybackDeliveryReadiness readiness,
        PlaybackDeliveryStartupPolicy startupPolicy,
        PlaybackDeliveryLeasePolicy leasePolicy,
        boolean refreshStartingOnFirstAttempt
    ) {
        PlaybackSession currentSession = session;
        int progressStage = -1;
        int attemptsInStage = 0;
        boolean firstAttempt = true;
        while (true) {
            if (refreshStartingOnFirstAttempt && firstAttempt && lifecyclePhase(currentSession) == PlaybackLifecyclePhase.STARTING) {
                currentSession = playbackSessionService.getSession(currentSession.sessionId(), owner);
                if (currentSession == null) {
                    return new PlaybackDeliveryTerminated(
                        null,
                        PlaybackLifecyclePhase.STOPPED,
                        "Playback session not found: " + session.sessionId()
                    );
                }
            }
            firstAttempt = false;

            PlaybackDeliveryOutcome terminalOutcome = terminalOutcome(currentSession);
            if (terminalOutcome != null) {
                maybeCloseStartupFailure(terminalOutcome, owner, leasePolicy);
                return terminalOutcome;
            }

            ReadinessEvaluation readinessEvaluation = readinessEvaluation(
                currentSession,
                owner,
                readiness,
                !refreshStartingOnFirstAttempt
            );
            if (readinessEvaluation.outcome() != null) {
                if (readinessEvaluation.outcome() instanceof PlaybackDeliveryUnavailable) {
                    maybeCloseStartupFailure(readinessEvaluation.outcome(), owner, leasePolicy);
                }
                return readinessEvaluation.outcome();
            }

            if (readinessEvaluation.progressStage() > progressStage) {
                progressStage = readinessEvaluation.progressStage();
                attemptsInStage = 0;
            }
            attemptsInStage += 1;
            if (attemptsInStage >= startupPolicy.pollAttempts()) {
                break;
            }
            sleep(startupPolicy.pollDelayMillis());
            currentSession = playbackSessionService.getSession(currentSession.sessionId(), owner);
            if (currentSession == null) {
                return new PlaybackDeliveryTerminated(
                    null,
                    PlaybackLifecyclePhase.STOPPED,
                    "Playback session not found: " + session.sessionId()
                );
            }
        }

        if (startupPolicy.timeoutAction() == PlaybackDeliveryTimeoutAction.FAIL) {
            PlaybackDeliveryFailed failed = timeoutFailure(currentSession, owner, readiness);
            maybeCloseStartupFailure(failed, owner, leasePolicy);
            return failed;
        }
        return new PlaybackDeliveryPending(currentSession, startupPolicy.retry());
    }

    private PlaybackDeliveryOutcome terminalOutcome(PlaybackSession session) {
        return switch (lifecyclePhase(session)) {
            case STARTING, READY -> null;
            case FAILED -> new PlaybackDeliveryFailed(
                session,
                session.failureCode(),
                session.failureMessage() == null
                    ? "Playback session failed: " + session.sessionId()
                    : session.failureMessage()
            );
            case STOPPED, ABANDONED -> new PlaybackDeliveryTerminated(
                session,
                lifecyclePhase(session),
                terminatedMessage(session)
            );
        };
    }

    private ReadinessEvaluation readinessEvaluation(
        PlaybackSession session,
        String owner,
        PlaybackDeliveryReadiness readiness,
        boolean unavailableWhenReadyArtifactMissing
    ) {
        if (lifecyclePhase(session) != PlaybackLifecyclePhase.READY) {
            return new ReadinessEvaluation(0, null);
        }
        if (readiness.requires(PlaybackDeliveryRequirement.DIRECT_FILE)) {
            Path path = playbackSessionService.getDirectContentPath(session.sessionId(), owner);
            return path == null
                ? new ReadinessEvaluation(
                    1,
                    new PlaybackDeliveryUnavailable(
                        session,
                        PlaybackDeliveryRequirement.DIRECT_FILE,
                        "Direct content not found for session: " + session.sessionId()
                    )
                )
                : new ReadinessEvaluation(1, new PlaybackDeliveryReadyFile(session, path));
        }
        if (readiness.requires(PlaybackDeliveryRequirement.HLS_MANIFEST)) {
            String backingJobId = backingJobId(session, owner, readiness);
            if (readiness.requires(PlaybackDeliveryRequirement.BACKING_JOB) && backingJobId == null) {
                return unavailableWhenReadyArtifactMissing
                    ? new ReadinessEvaluation(
                        0,
                        new PlaybackDeliveryUnavailable(
                            session,
                            PlaybackDeliveryRequirement.BACKING_JOB,
                            "Playback session did not create a backing transcode job"
                        )
                    )
                    : new ReadinessEvaluation(0, null);
            }
            String manifest = playbackSessionService.getHlsManifest(session.sessionId(), owner);
            if (manifest == null) {
                return unavailableWhenReadyArtifactMissing
                    ? new ReadinessEvaluation(
                        backingJobId == null ? 0 : 1,
                        new PlaybackDeliveryUnavailable(
                            session,
                            PlaybackDeliveryRequirement.HLS_MANIFEST,
                            "HLS manifest not found for session: " + session.sessionId()
                        )
                    )
                    : new ReadinessEvaluation(backingJobId == null ? 0 : 1, null);
            }
            return new ReadinessEvaluation(
                backingJobId == null ? 0 : 1,
                new PlaybackDeliveryReadyManifest(session, StreamingProtocol.HLS, manifest, backingJobId)
            );
        }
        if (readiness.requires(PlaybackDeliveryRequirement.DASH_MANIFEST)) {
            String backingJobId = backingJobId(session, owner, readiness);
            if (readiness.requires(PlaybackDeliveryRequirement.BACKING_JOB) && backingJobId == null) {
                return unavailableWhenReadyArtifactMissing
                    ? new ReadinessEvaluation(
                        0,
                        new PlaybackDeliveryUnavailable(
                            session,
                            PlaybackDeliveryRequirement.BACKING_JOB,
                            "Playback session did not create a backing transcode job"
                        )
                    )
                    : new ReadinessEvaluation(0, null);
            }
            String manifest = playbackSessionService.getDashManifest(session.sessionId(), owner);
            if (manifest == null) {
                return unavailableWhenReadyArtifactMissing
                    ? new ReadinessEvaluation(
                        backingJobId == null ? 0 : 1,
                        new PlaybackDeliveryUnavailable(
                            session,
                            PlaybackDeliveryRequirement.DASH_MANIFEST,
                            "DASH manifest not found for session: " + session.sessionId()
                        )
                    )
                    : new ReadinessEvaluation(backingJobId == null ? 0 : 1, null);
            }
            return new ReadinessEvaluation(
                backingJobId == null ? 0 : 1,
                new PlaybackDeliveryReadyManifest(session, StreamingProtocol.DASH, manifest, backingJobId)
            );
        }
        PlaybackSessionArtifacts artifacts = session.artifacts();
        return new ReadinessEvaluation(
            1,
            new PlaybackDeliveryReadyManifest(
                session,
                artifacts == null ? readiness.manifestProtocol() : artifacts.protocol(),
                null,
                null
            )
        );
    }

    private PlaybackDeliveryFailed timeoutFailure(
        PlaybackSession session,
        String owner,
        PlaybackDeliveryReadiness readiness
    ) {
        if (readiness.requires(PlaybackDeliveryRequirement.BACKING_JOB) && backingJobId(session, owner, readiness) == null) {
            return new PlaybackDeliveryFailed(
                session,
                FAILURE_JOB_NOT_FOUND,
                "Playback session did not create a backing transcode job"
            );
        }
        if (readiness.requires(PlaybackDeliveryRequirement.HLS_MANIFEST)) {
            return new PlaybackDeliveryFailed(
                session,
                FAILURE_TRANSCODE_FAILED,
                "Playback session did not produce an HLS manifest before the delivery timeout: " + session.sessionId()
            );
        }
        if (readiness.requires(PlaybackDeliveryRequirement.DASH_MANIFEST)) {
            return new PlaybackDeliveryFailed(
                session,
                FAILURE_TRANSCODE_FAILED,
                "Playback session did not produce a DASH manifest before the delivery timeout: " + session.sessionId()
            );
        }
        if (readiness.requires(PlaybackDeliveryRequirement.DIRECT_FILE)) {
            return new PlaybackDeliveryFailed(
                session,
                FAILURE_JOB_NOT_FOUND,
                "Direct content not found for session: " + session.sessionId()
            );
        }
        return new PlaybackDeliveryFailed(
            session,
            FAILURE_TRANSCODE_FAILED,
            "Playback session did not become ready before the delivery timeout: " + session.sessionId()
        );
    }

    private String backingJobId(
        PlaybackSession session,
        String owner,
        PlaybackDeliveryReadiness readiness
    ) {
        if (!readiness.requires(PlaybackDeliveryRequirement.BACKING_JOB)) {
            return null;
        }
        String jobId = playbackSessionService.getSessionJobId(session.sessionId(), owner);
        return jobId == null || jobId.isBlank() ? null : jobId;
    }

    private void updateLeaseState(
        PlaybackDeliveryOutcome outcome,
        PlaybackDeliveryLeasePolicy leasePolicy,
        String owner
    ) {
        PlaybackSession session = outcome.session();
        if (session == null) {
            return;
        }
        if (
            outcome instanceof PlaybackDeliveryFailed ||
                outcome instanceof PlaybackDeliveryTerminated ||
                outcome instanceof PlaybackDeliveryUnavailable && leasePolicy.closeOnStartupFailure()
        ) {
            clearLeaseMapping(null, session.sessionId());
            clearLease(session.sessionId(), owner);
            return;
        }
        if (outcome instanceof PlaybackDeliveryReadyManifest) {
            clearLeaseMapping(null, session.sessionId());
        }
        if (leasePolicy.enabled() && leasePolicy.leaseOpenedSession()) {
            refreshLease(session.sessionId(), owner, leasePolicy.leaseKey(), leasePolicy.idleTtlMillis());
            return;
        }
        refreshExistingLease(session.sessionId());
    }

    private void refreshExistingLease(String sessionId) {
        DeliveryLease lease = leases.get(sessionId);
        if (lease != null) {
            refreshLease(sessionId, lease.owner(), lease.leaseKey(), lease.idleTtlMillis());
        }
    }

    private void refreshLease(String sessionId, String owner, String leaseKey, long idleTtlMillis) {
        if (idleTtlMillis <= 0L) {
            return;
        }
        long expiresAtMillis = clock.millis() + idleTtlMillis;
        ScheduledFuture<?> cleanupFuture = cleanupScheduler.schedule(
            () -> expireLease(sessionId, expiresAtMillis),
            idleTtlMillis,
            TimeUnit.MILLISECONDS
        );
        DeliveryLease previousLease = leases.put(
            sessionId,
            new DeliveryLease(owner, leaseKey, idleTtlMillis, expiresAtMillis, cleanupFuture)
        );
        cancelCleanup(previousLease);
    }

    private void expireLease(String sessionId, long expectedExpiresAtMillis) {
        DeliveryLease lease = leases.get(sessionId);
        if (lease == null || lease.expiresAtMillis() > expectedExpiresAtMillis) {
            return;
        }
        long remainingMillis = lease.expiresAtMillis() - clock.millis();
        if (remainingMillis > 0L) {
            ScheduledFuture<?> cleanupFuture = cleanupScheduler.schedule(
                () -> expireLease(sessionId, lease.expiresAtMillis()),
                remainingMillis,
                TimeUnit.MILLISECONDS
            );
            DeliveryLease replacement = new DeliveryLease(
                lease.owner(),
                lease.leaseKey(),
                lease.idleTtlMillis(),
                lease.expiresAtMillis(),
                cleanupFuture
            );
            if (leases.replace(sessionId, lease, replacement)) {
                cancelCleanup(lease);
            } else {
                cancelCleanup(replacement);
            }
            return;
        }
        if (!leases.remove(sessionId, lease)) {
            return;
        }
        clearLeaseMapping(lease.leaseKey(), sessionId);
        closeSessionQuietly(sessionId, lease.owner());
    }

    private void cleanupStartupException(
        PlaybackSession session,
        String owner,
        PlaybackDeliveryLeasePolicy leasePolicy
    ) {
        if (session == null) {
            return;
        }
        if (leasePolicy.closeOnStartupFailure()) {
            clearDeliveryStateForOwner(session.sessionId(), owner);
            closeSessionQuietly(session.sessionId(), owner);
            return;
        }
        if (leasePolicy.enabled() && leasePolicy.leaseOpenedSession()) {
            sessionsByLeaseKey.put(leasePolicy.leaseKey(), session.sessionId());
            leaseKeysBySession.put(session.sessionId(), leasePolicy.leaseKey());
            refreshLease(session.sessionId(), owner, leasePolicy.leaseKey(), leasePolicy.idleTtlMillis());
            return;
        }
        refreshExistingLease(session.sessionId());
    }

    private void maybeCloseStartupFailure(
        PlaybackDeliveryOutcome outcome,
        String owner,
        PlaybackDeliveryLeasePolicy leasePolicy
    ) {
        PlaybackSession session = outcome.session();
        if (session != null && leasePolicy.closeOnStartupFailure()) {
            closeSessionQuietly(session.sessionId(), owner);
        }
    }

    private void clearDeliveryStateForOwner(String sessionId, String owner) {
        if (sessionId == null) {
            return;
        }
        DeliveryLease lease = leases.get(sessionId);
        if (lease != null && !Objects.equals(lease.owner(), owner)) {
            return;
        }
        clearLeaseMapping(null, sessionId);
        clearLease(sessionId, owner);
    }

    private void clearLease(String sessionId, String owner) {
        DeliveryLease lease = leases.get(sessionId);
        if (lease != null && Objects.equals(lease.owner(), owner) && leases.remove(sessionId, lease)) {
            cancelCleanup(lease);
        }
    }

    private void clearLeaseMapping(String leaseKey, String sessionId) {
        if (sessionId == null) {
            if (leaseKey != null) {
                String removedSessionId = sessionsByLeaseKey.remove(leaseKey);
                if (removedSessionId != null) {
                    leaseKeysBySession.remove(removedSessionId, leaseKey);
                }
            }
            return;
        }

        if (leaseKey == null) {
            String removedLeaseKey = leaseKeysBySession.remove(sessionId);
            if (removedLeaseKey != null) {
                sessionsByLeaseKey.remove(removedLeaseKey, sessionId);
            }
            return;
        }

        if (sessionsByLeaseKey.remove(leaseKey, sessionId)) {
            leaseKeysBySession.remove(sessionId, leaseKey);
        }
    }

    private void closeDuplicateSession(
        String sessionId,
        String owner,
        PlaybackDeliveryLeasePolicy leasePolicy
    ) {
        if (leasePolicy.closeDuplicateSessions()) {
            closeSessionQuietly(sessionId, owner);
        }
    }

    private void closeSessionQuietly(String sessionId, String owner) {
        try {
            playbackSessionService.closeSession(sessionId, owner);
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean isReusableForLease(
        PlaybackSession session,
        PlaybackDeliveryLeasePolicy leasePolicy
    ) {
        if (session == null) {
            return false;
        }
        return switch (lifecyclePhase(session)) {
            case STARTING -> leasePolicy.reuseStartingSessions();
            case READY -> isReadyFileSession(session) && leasePolicy.reuseReadyFileSessions();
            case FAILED, STOPPED, ABANDONED -> false;
        };
    }

    private static boolean isReadyFileSession(PlaybackSession session) {
        PlaybackSessionArtifacts artifacts = session.artifacts();
        return lifecyclePhase(session) == PlaybackLifecyclePhase.READY &&
            artifacts != null &&
            artifacts.protocol() == StreamingProtocol.FILE;
    }

    private static PlaybackLifecyclePhase lifecyclePhase(PlaybackSession session) {
        PlaybackSessionLifecycle lifecycle = session.lifecycle();
        if (lifecycle != null && lifecycle.phase() != null) {
            return lifecycle.phase();
        }
        PlaybackSessionState state = session.state();
        return switch (state) {
            case PENDING -> PlaybackLifecyclePhase.STARTING;
            case READY -> PlaybackLifecyclePhase.READY;
            case FAILED -> PlaybackLifecyclePhase.FAILED;
            case CLOSED -> PlaybackLifecyclePhase.STOPPED;
        };
    }

    private static String terminatedMessage(PlaybackSession session) {
        return switch (lifecyclePhase(session)) {
            case STOPPED -> "Playback session stopped: " + session.sessionId();
            case ABANDONED -> "Playback session abandoned: " + session.sessionId();
            default -> "Playback session unavailable: " + session.sessionId();
        };
    }

    private static void cancelCleanup(DeliveryLease lease) {
        if (lease != null && lease.cleanupFuture() != null) {
            lease.cleanupFuture().cancel(false);
        }
    }

    private static void sleep(long delayMillis) {
        if (delayMillis == 0L) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
    }

    private record DeliveryLease(
        String owner,
        String leaseKey,
        long idleTtlMillis,
        long expiresAtMillis,
        ScheduledFuture<?> cleanupFuture
    ) {
    }

    private record ReadinessEvaluation(
        int progressStage,
        PlaybackDeliveryOutcome outcome
    ) {
    }
}
