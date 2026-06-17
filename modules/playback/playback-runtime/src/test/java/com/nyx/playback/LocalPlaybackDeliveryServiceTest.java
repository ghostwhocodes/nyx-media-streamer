package com.nyx.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.media.contracts.MediaKind;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.nyx.playback.contracts.MediaSourceRef;
import com.nyx.playback.contracts.PlaybackDeliveryFailed;
import com.nyx.playback.contracts.PlaybackDeliveryLeasePolicy;
import com.nyx.playback.contracts.PlaybackDeliveryOutcome;
import com.nyx.playback.contracts.PlaybackDeliveryPending;
import com.nyx.playback.contracts.PlaybackDeliveryReadyFile;
import com.nyx.playback.contracts.PlaybackDeliveryReadyManifest;
import com.nyx.playback.contracts.PlaybackDeliveryReadiness;
import com.nyx.playback.contracts.PlaybackDeliveryRequest;
import com.nyx.playback.contracts.PlaybackDeliveryRequirement;
import com.nyx.playback.contracts.PlaybackDeliverySessionRequest;
import com.nyx.playback.contracts.PlaybackDeliveryStartupPolicy;
import com.nyx.playback.contracts.PlaybackDeliveryTerminated;
import com.nyx.playback.contracts.PlaybackDeliveryTimeoutAction;
import com.nyx.playback.contracts.PlaybackDeliveryUnavailable;
import com.nyx.playback.contracts.PlaybackLifecyclePhase;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSession;
import com.nyx.playback.contracts.PlaybackSessionArtifacts;
import com.nyx.playback.contracts.PlaybackSessionLifecycle;
import com.nyx.playback.contracts.PlaybackSessionService;
import com.nyx.playback.contracts.PlaybackSessionState;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class LocalPlaybackDeliveryServiceTest {
    @Test
    void returnsReadyHlsManifestWithBackingJobWhenAllReadinessRequirementsAreMet() {
        StubPlaybackSessionService sessions = new StubPlaybackSessionService();
        sessions.enqueueOpen(readySession("session-hls", StreamingProtocol.HLS));
        sessions.hlsManifests.put("session-hls", "#EXTM3U\n");
        sessions.jobIds.put("session-hls", "job-1");
        LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions);

        PlaybackDeliveryOutcome outcome = delivery.open(new PlaybackDeliveryRequest(
            playbackRequest(),
            "alice",
            PlaybackDeliveryReadiness.hlsManifestWithBackingJob(),
            failFastPolicy(),
            new PlaybackDeliveryLeasePolicy()
        ));

        PlaybackDeliveryReadyManifest manifest = assertInstanceOf(PlaybackDeliveryReadyManifest.class, outcome);
        assertEquals("session-hls", manifest.sessionId());
        assertEquals(StreamingProtocol.HLS, manifest.protocol());
        assertEquals("#EXTM3U\n", manifest.manifest());
        assertEquals("job-1", manifest.backingJobId());
        assertEquals(List.of("session-hls:alice"), sessions.jobLookups);
        assertEquals(List.of("session-hls:alice"), sessions.hlsLookups);
    }

    @Test
    void returnsReadyDirectFileWithoutAdapterCallingArtifactGetters() {
        StubPlaybackSessionService sessions = new StubPlaybackSessionService();
        sessions.enqueueOpen(readySession("session-file", StreamingProtocol.FILE));
        sessions.directFiles.put("session-file", Path.of("/media/direct.mp4"));
        LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions);

        PlaybackDeliveryOutcome outcome = delivery.open(new PlaybackDeliveryRequest(
            playbackRequest(),
            "alice",
            PlaybackDeliveryReadiness.directFile(),
            failFastPolicy(),
            new PlaybackDeliveryLeasePolicy()
        ));

        PlaybackDeliveryReadyFile file = assertInstanceOf(PlaybackDeliveryReadyFile.class, outcome);
        assertEquals("session-file", file.sessionId());
        assertEquals(Path.of("/media/direct.mp4"), file.path());
        assertEquals(List.of("session-file:alice"), sessions.directLookups);
    }

    @Test
    void returnsPendingRetryMetadataAndReusesStartingSessionForRetriedStartup() {
        StubPlaybackSessionService sessions = new StubPlaybackSessionService();
        sessions.enqueueOpen(startingSession("session-pending"));
        LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions);
        PlaybackDeliveryLeasePolicy leasePolicy = new PlaybackDeliveryLeasePolicy("alice:/media/movie.mkv:medium", 60_000L);
        PlaybackDeliveryRequest request = new PlaybackDeliveryRequest(
            playbackRequest(),
            "alice",
            PlaybackDeliveryReadiness.hlsManifest(),
            new PlaybackDeliveryStartupPolicy(1, 0L),
            leasePolicy
        );

        try {
            PlaybackDeliveryPending first = assertInstanceOf(PlaybackDeliveryPending.class, delivery.open(request));
            PlaybackDeliveryPending second = assertInstanceOf(PlaybackDeliveryPending.class, delivery.open(request));

            assertEquals("session-pending", first.sessionId());
            assertEquals(2, first.retry().retryAfterSeconds());
            assertEquals("pending", first.retry().status());
            assertEquals("session-pending", second.sessionId());
            assertEquals(1, sessions.openRequests.size());
        } finally {
            delivery.shutdown();
        }
    }

    @Test
    void refreshesOpenedStartingSessionBeforeFirstPendingDecision() {
        RefreshingPlaybackSessionService sessions = new RefreshingPlaybackSessionService(
            readySession("session-refresh", StreamingProtocol.HLS)
        );
        sessions.enqueueOpen(startingSession("session-refresh"));
        sessions.hlsManifests.put("session-refresh", "#EXTM3U\n");
        LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions);

        PlaybackDeliveryOutcome outcome = delivery.open(new PlaybackDeliveryRequest(
            playbackRequest(),
            "alice",
            PlaybackDeliveryReadiness.hlsManifest(),
            new PlaybackDeliveryStartupPolicy(1, 0L),
            new PlaybackDeliveryLeasePolicy()
        ));

        PlaybackDeliveryReadyManifest manifest = assertInstanceOf(PlaybackDeliveryReadyManifest.class, outcome);
        assertEquals("session-refresh", manifest.sessionId());
        assertEquals("#EXTM3U\n", manifest.manifest());
        assertEquals(List.of("session-refresh:alice"), sessions.sessionLookups);
    }

    @Test
    void observesReadyManifestMissingAsUnavailableDeliveryOutcome() {
        StubPlaybackSessionService sessions = new StubPlaybackSessionService();
        sessions.sessions.put("session-missing-manifest", readySession("session-missing-manifest", StreamingProtocol.HLS));
        LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions);

        PlaybackDeliveryOutcome outcome = delivery.observe(new PlaybackDeliverySessionRequest(
            "session-missing-manifest",
            "alice",
            PlaybackDeliveryReadiness.hlsManifest(),
            new PlaybackDeliveryStartupPolicy(1, 0L),
            new PlaybackDeliveryLeasePolicy()
        ));

        PlaybackDeliveryUnavailable unavailable = assertInstanceOf(PlaybackDeliveryUnavailable.class, outcome);
        assertEquals("session-missing-manifest", unavailable.sessionId());
        assertEquals(PlaybackDeliveryRequirement.HLS_MANIFEST, unavailable.requirement());
        assertEquals(List.of("session-missing-manifest:alice"), sessions.hlsLookups);
    }

    @Test
    void givesManifestReadinessANewPollBudgetAfterBackingJobAppears() {
        class DelayedReadinessPlaybackSessionService extends StubPlaybackSessionService {
            private int jobLookupCount;
            private int manifestLookupCount;

            @Override
            public String getSessionJobId(String sessionId, String owner) {
                jobLookupCount += 1;
                return jobLookupCount < 2 ? null : "job-1";
            }

            @Override
            public String getHlsManifest(String sessionId, String owner) {
                manifestLookupCount += 1;
                return manifestLookupCount < 2 ? null : "#EXTM3U\n";
            }
        }
        DelayedReadinessPlaybackSessionService sessions = new DelayedReadinessPlaybackSessionService();
        sessions.enqueueOpen(readySession("session-staged", StreamingProtocol.HLS));
        LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions);

        PlaybackDeliveryOutcome outcome = delivery.open(new PlaybackDeliveryRequest(
            playbackRequest(),
            "alice",
            PlaybackDeliveryReadiness.hlsManifestWithBackingJob(),
            new PlaybackDeliveryStartupPolicy(2, 0L, PlaybackDeliveryTimeoutAction.FAIL, null),
            new PlaybackDeliveryLeasePolicy()
        ));

        PlaybackDeliveryReadyManifest manifest = assertInstanceOf(PlaybackDeliveryReadyManifest.class, outcome);
        assertEquals("session-staged", manifest.sessionId());
        assertEquals("job-1", manifest.backingJobId());
        assertEquals("#EXTM3U\n", manifest.manifest());
        assertTrue(sessions.jobLookupCount >= 2);
        assertEquals(2, sessions.manifestLookupCount);
    }

    @Test
    void failsStartupWhenBackingJobNeverMaterializesAndClosesByPolicy() {
        StubPlaybackSessionService sessions = new StubPlaybackSessionService();
        sessions.enqueueOpen(readySession("session-no-job", StreamingProtocol.HLS));
        sessions.hlsManifests.put("session-no-job", "#EXTM3U\n");
        LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions);
        PlaybackDeliveryLeasePolicy closeOnFailure = new PlaybackDeliveryLeasePolicy(
            null,
            0L,
            false,
            false,
            true,
            true,
            false
        );

        PlaybackDeliveryOutcome outcome = delivery.open(new PlaybackDeliveryRequest(
            playbackRequest(),
            "alice",
            PlaybackDeliveryReadiness.hlsManifestWithBackingJob(),
            failFastPolicy(),
            closeOnFailure
        ));

        PlaybackDeliveryFailed failed = assertInstanceOf(PlaybackDeliveryFailed.class, outcome);
        assertEquals("JOB_NOT_FOUND", failed.failureCode());
        assertTrue(failed.message().contains("backing transcode job"));
        assertEquals(List.of(new CloseRequest("session-no-job", "alice")), sessions.closeRequests);
    }

    @Test
    void clearsLeaseAndClosesOpenedSessionWhenReadinessPollingThrows() {
        class ThrowingRefreshPlaybackSessionService extends StubPlaybackSessionService {
            private boolean throwOnRefresh = true;

            @Override
            public PlaybackSession getSession(String sessionId, String owner) {
                sessionLookups.add(sessionId + ":" + owner);
                if (throwOnRefresh) {
                    throwOnRefresh = false;
                    throw new IllegalStateException("readiness poll failed");
                }
                return sessions.get(sessionId);
            }
        }
        ThrowingRefreshPlaybackSessionService sessions = new ThrowingRefreshPlaybackSessionService();
        sessions.enqueueOpen(startingSession("session-throws"));
        LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions);
        PlaybackDeliveryLeasePolicy leasePolicy = new PlaybackDeliveryLeasePolicy(
            "alice:/media/throws.mkv:hls",
            60_000L,
            true,
            true,
            true,
            true,
            true
        );
        PlaybackDeliveryRequest request = new PlaybackDeliveryRequest(
            playbackRequest(),
            "alice",
            PlaybackDeliveryReadiness.hlsManifest(),
            new PlaybackDeliveryStartupPolicy(1, 0L),
            leasePolicy
        );

        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> delivery.open(request));

            assertEquals("readiness poll failed", failure.getMessage());
            assertEquals(List.of(new CloseRequest("session-throws", "alice")), sessions.closeRequests);

            sessions.enqueueOpen(startingSession("session-next"));
            PlaybackDeliveryPending pending = assertInstanceOf(PlaybackDeliveryPending.class, delivery.open(request));

            assertEquals("session-next", pending.sessionId());
            assertEquals(2, sessions.openRequests.size());
        } finally {
            delivery.shutdown();
        }
    }

    @Test
    void preservesIdleLeaseWhenReadinessPollingThrowsWithoutCloseOnStartupFailure() throws Exception {
        ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            class ThrowingRefreshPlaybackSessionService extends StubPlaybackSessionService {
                private boolean throwOnRefresh = true;

                @Override
                public PlaybackSession getSession(String sessionId, String owner) {
                    sessionLookups.add(sessionId + ":" + owner);
                    if (throwOnRefresh) {
                        throwOnRefresh = false;
                        throw new IllegalStateException("readiness poll failed");
                    }
                    return sessions.get(sessionId);
                }
            }
            ThrowingRefreshPlaybackSessionService sessions = new ThrowingRefreshPlaybackSessionService();
            sessions.enqueueOpen(startingSession("session-lease-throws"));
            LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions, cleanupScheduler);
            PlaybackDeliveryLeasePolicy leasePolicy = new PlaybackDeliveryLeasePolicy(
                "alice:/media/lease-throws.mkv:hls",
                25L
            );
            PlaybackDeliveryRequest request = new PlaybackDeliveryRequest(
                playbackRequest(),
                "alice",
                PlaybackDeliveryReadiness.hlsManifest(),
                new PlaybackDeliveryStartupPolicy(1, 0L),
                leasePolicy
            );

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> delivery.open(request));

            assertEquals("readiness poll failed", failure.getMessage());
            assertTrue(sessions.closeRequests.isEmpty());
            assertEventually(() -> sessions.closeRequests.contains(new CloseRequest("session-lease-throws", "alice")));
        } finally {
            cleanupScheduler.shutdownNow();
        }
    }

    @Test
    void keepsIdleLeaseForReadyDirectFileMissingPathUntilCleanupExpires() throws Exception {
        ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            StubPlaybackSessionService sessions = new StubPlaybackSessionService();
            sessions.enqueueOpen(readySession("session-missing-file", StreamingProtocol.FILE));
            LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions, cleanupScheduler);
            PlaybackDeliveryLeasePolicy leasePolicy = new PlaybackDeliveryLeasePolicy(
                "alice:/media/missing-file.mp4:file",
                25L
            );

            PlaybackDeliveryOutcome outcome = delivery.open(new PlaybackDeliveryRequest(
                playbackRequest(),
                "alice",
                PlaybackDeliveryReadiness.directFile(),
                failFastPolicy(),
                leasePolicy
            ));

            PlaybackDeliveryUnavailable unavailable = assertInstanceOf(PlaybackDeliveryUnavailable.class, outcome);
            assertEquals(PlaybackDeliveryRequirement.DIRECT_FILE, unavailable.requirement());
            assertEventually(() -> sessions.closeRequests.contains(new CloseRequest("session-missing-file", "alice")));
        } finally {
            cleanupScheduler.shutdownNow();
        }
    }

    @Test
    void defaultConstructorExpiresIdleLeaseWhenNoSchedulerIsSupplied() throws Exception {
        StubPlaybackSessionService sessions = new StubPlaybackSessionService();
        sessions.enqueueOpen(readySession("session-default-idle", StreamingProtocol.FILE));
        sessions.directFiles.put("session-default-idle", Path.of("/media/default-direct.mp4"));
        LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions);
        PlaybackDeliveryLeasePolicy leasePolicy = new PlaybackDeliveryLeasePolicy(
            "alice:/media/default-direct.mp4:file",
            25L
        );

        try {
            PlaybackDeliveryReadyFile file = assertInstanceOf(PlaybackDeliveryReadyFile.class, delivery.open(
                new PlaybackDeliveryRequest(
                    playbackRequest(),
                    "alice",
                    PlaybackDeliveryReadiness.directFile(),
                    failFastPolicy(),
                    leasePolicy
                )
            ));

            assertEquals("session-default-idle", file.sessionId());
            assertEventually(() -> sessions.closeRequests.contains(new CloseRequest("session-default-idle", "alice")));
        } finally {
            delivery.shutdown();
        }
    }

    @Test
    void returnsFailedAndTerminatedOutcomesForTerminalSessions() {
        StubPlaybackSessionService failedSessions = new StubPlaybackSessionService();
        failedSessions.enqueueOpen(failedSession("session-failed"));
        LocalPlaybackDeliveryService failedDelivery = new LocalPlaybackDeliveryService(failedSessions);

        PlaybackDeliveryFailed failed = assertInstanceOf(PlaybackDeliveryFailed.class, failedDelivery.open(
            deliveryRequest(PlaybackDeliveryReadiness.hlsManifest())
        ));

        StubPlaybackSessionService stoppedSessions = new StubPlaybackSessionService();
        stoppedSessions.enqueueOpen(stoppedSession("session-stopped"));
        LocalPlaybackDeliveryService stoppedDelivery = new LocalPlaybackDeliveryService(stoppedSessions);

        PlaybackDeliveryTerminated terminated = assertInstanceOf(PlaybackDeliveryTerminated.class, stoppedDelivery.open(
            deliveryRequest(PlaybackDeliveryReadiness.hlsManifest())
        ));

        assertEquals("transcode failed", failed.message());
        assertEquals(PlaybackLifecyclePhase.STOPPED, terminated.phase());
        assertTrue(terminated.message().contains("stopped"));
    }

    @Test
    void closesDuplicateStartingSessionWhenConcurrentOpenRacesForSameLeaseKey() throws Exception {
        BlockingOpenPlaybackSessionService sessions = new BlockingOpenPlaybackSessionService(
            startingSession("session-race-1"),
            startingSession("session-race-2")
        );
        LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions);
        PlaybackDeliveryLeasePolicy leasePolicy = new PlaybackDeliveryLeasePolicy("alice:/media/race.mkv:medium", 60_000L);
        PlaybackDeliveryRequest request = new PlaybackDeliveryRequest(
            playbackRequest(),
            "alice",
            PlaybackDeliveryReadiness.hlsManifest(),
            new PlaybackDeliveryStartupPolicy(1, 0L),
            leasePolicy
        );
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> delivery.open(request));
            var second = executor.submit(() -> delivery.open(request));

            PlaybackDeliveryPending firstOutcome = assertInstanceOf(
                PlaybackDeliveryPending.class,
                first.get(5, TimeUnit.SECONDS)
            );
            PlaybackDeliveryPending secondOutcome = assertInstanceOf(
                PlaybackDeliveryPending.class,
                second.get(5, TimeUnit.SECONDS)
            );

            assertEquals(firstOutcome.sessionId(), secondOutcome.sessionId());
            assertEquals(2, sessions.openRequests.size());
            assertEquals(1, sessions.closeRequests.size());
            assertTrue(
                sessions.closeRequests.contains(new CloseRequest("session-race-1", "alice")) ||
                    sessions.closeRequests.contains(new CloseRequest("session-race-2", "alice"))
            );
        } finally {
            delivery.shutdown();
            executor.shutdownNow();
        }
    }

    @Test
    void expiresIdleLeaseAndClosesOwnedSession() throws Exception {
        ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            StubPlaybackSessionService sessions = new StubPlaybackSessionService();
            sessions.enqueueOpen(readySession("session-idle", StreamingProtocol.FILE));
            sessions.directFiles.put("session-idle", Path.of("/media/direct.mp4"));
            LocalPlaybackDeliveryService delivery = new LocalPlaybackDeliveryService(sessions, cleanupScheduler);
            PlaybackDeliveryLeasePolicy leasePolicy = new PlaybackDeliveryLeasePolicy("alice:/media/direct.mp4:file", 25L);

            PlaybackDeliveryReadyFile file = assertInstanceOf(PlaybackDeliveryReadyFile.class, delivery.open(
                new PlaybackDeliveryRequest(
                    playbackRequest(),
                    "alice",
                    PlaybackDeliveryReadiness.directFile(),
                    failFastPolicy(),
                    leasePolicy
                )
            ));

            assertEquals("session-idle", file.sessionId());
            assertEventually(() -> sessions.closeRequests.contains(new CloseRequest("session-idle", "alice")));
        } finally {
            cleanupScheduler.shutdownNow();
        }
    }

    private static PlaybackDeliveryRequest deliveryRequest(PlaybackDeliveryReadiness readiness) {
        return new PlaybackDeliveryRequest(
            playbackRequest(),
            "alice",
            readiness,
            failFastPolicy(),
            new PlaybackDeliveryLeasePolicy()
        );
    }

    private static PlaybackDeliveryStartupPolicy failFastPolicy() {
        return new PlaybackDeliveryStartupPolicy(
            1,
            0L,
            PlaybackDeliveryTimeoutAction.FAIL,
            null
        );
    }

    private static PlaybackRequest playbackRequest() {
        return new PlaybackRequest(new MediaSourceRef("/media/movie.mkv"));
    }

    private static PlaybackSession readySession(String sessionId, StreamingProtocol protocol) {
        return session(sessionId, PlaybackLifecyclePhase.READY, protocol, null);
    }

    private static PlaybackSession startingSession(String sessionId) {
        return session(sessionId, PlaybackLifecyclePhase.STARTING, StreamingProtocol.HLS, null);
    }

    private static PlaybackSession failedSession(String sessionId) {
        return session(sessionId, PlaybackLifecyclePhase.FAILED, StreamingProtocol.HLS, "transcode failed");
    }

    private static PlaybackSession stoppedSession(String sessionId) {
        return session(sessionId, PlaybackLifecyclePhase.STOPPED, StreamingProtocol.HLS, null);
    }

    private static PlaybackSession session(
        String sessionId,
        PlaybackLifecyclePhase phase,
        StreamingProtocol protocol,
        String failureMessage
    ) {
        PlaybackSessionState state = switch (phase) {
            case STARTING -> PlaybackSessionState.PENDING;
            case READY -> PlaybackSessionState.READY;
            case FAILED -> PlaybackSessionState.FAILED;
            case STOPPED, ABANDONED -> PlaybackSessionState.CLOSED;
        };
        return new PlaybackSession(
            sessionId,
            "object-" + sessionId,
            MediaKind.VIDEO,
            state,
            null,
            new PlaybackSessionArtifacts(
                protocol,
                "/api/v1/playback/sessions/" + sessionId + "/master.m3u8",
                protocol == StreamingProtocol.FILE ? "/api/v1/playback/sessions/" + sessionId + "/content" : null,
                protocol == StreamingProtocol.HLS ? "/api/v1/playback/sessions/" + sessionId + "/master.m3u8" : null,
                protocol == StreamingProtocol.DASH ? "/api/v1/playback/sessions/" + sessionId + "/manifest.mpd" : null
            ),
            null,
            phase == PlaybackLifecyclePhase.FAILED ? "TRANSCODE_FAILED" : null,
            failureMessage,
            "2026-06-13T12:00:00Z",
            new PlaybackSessionLifecycle(
                phase,
                "2026-06-13T12:00:00Z",
                phase == PlaybackLifecyclePhase.READY ? "2026-06-13T12:00:01Z" : null,
                phase == PlaybackLifecyclePhase.STOPPED ? "2026-06-13T12:00:02Z" : null,
                phase == PlaybackLifecyclePhase.READY ? 100.0 : 10.0,
                phase != PlaybackLifecyclePhase.STOPPED,
                null
            )
        );
    }

    private static void assertEventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(condition.getAsBoolean(), "Condition did not become true before timeout");
    }

    private static class StubPlaybackSessionService implements PlaybackSessionService {
        private final Queue<PlaybackSession> openResponses = new ConcurrentLinkedQueue<>();
        protected final Map<String, PlaybackSession> sessions = new ConcurrentHashMap<>();
        protected final Map<String, String> hlsManifests = new ConcurrentHashMap<>();
        private final Map<String, String> dashManifests = new ConcurrentHashMap<>();
        private final Map<String, String> jobIds = new ConcurrentHashMap<>();
        private final Map<String, Path> directFiles = new ConcurrentHashMap<>();
        protected final List<PlaybackRequest> openRequests = new CopyOnWriteArrayList<>();
        protected final List<String> sessionLookups = new CopyOnWriteArrayList<>();
        private final List<String> hlsLookups = new CopyOnWriteArrayList<>();
        private final List<String> jobLookups = new CopyOnWriteArrayList<>();
        private final List<String> directLookups = new CopyOnWriteArrayList<>();
        protected final List<CloseRequest> closeRequests = new CopyOnWriteArrayList<>();

        void enqueueOpen(PlaybackSession session) {
            openResponses.add(session);
        }

        @Override
        public PlaybackSession openSession(PlaybackRequest request, String owner) {
            openRequests.add(request);
            PlaybackSession session = openResponses.remove();
            sessions.put(session.sessionId(), session);
            return session;
        }

        @Override
        public PlaybackSession getSession(String sessionId, String owner) {
            sessionLookups.add(sessionId + ":" + owner);
            return sessions.get(sessionId);
        }

        @Override
        public PlaybackSession reportPlayback(String sessionId, MediaSessionPlaybackReport report, String owner) {
            return sessions.get(sessionId);
        }

        @Override
        public String getSessionJobId(String sessionId, String owner) {
            jobLookups.add(sessionId + ":" + owner);
            return jobIds.get(sessionId);
        }

        @Override
        public void closeSession(String sessionId, String owner) {
            closeRequests.add(new CloseRequest(sessionId, owner));
        }

        @Override
        public String getHlsManifest(String sessionId, String owner) {
            hlsLookups.add(sessionId + ":" + owner);
            return hlsManifests.get(sessionId);
        }

        @Override
        public String getDashManifest(String sessionId, String owner) {
            return dashManifests.get(sessionId);
        }

        @Override
        public Path getDirectContentPath(String sessionId, String owner) {
            directLookups.add(sessionId + ":" + owner);
            return directFiles.get(sessionId);
        }
    }

    private static final class RefreshingPlaybackSessionService extends StubPlaybackSessionService {
        private final PlaybackSession refreshedSession;

        private RefreshingPlaybackSessionService(PlaybackSession refreshedSession) {
            this.refreshedSession = refreshedSession;
        }

        @Override
        public PlaybackSession getSession(String sessionId, String owner) {
            sessionLookups.add(sessionId + ":" + owner);
            sessions.put(sessionId, refreshedSession);
            return refreshedSession;
        }
    }

    private static final class BlockingOpenPlaybackSessionService extends StubPlaybackSessionService {
        private final Queue<PlaybackSession> responses = new ConcurrentLinkedQueue<>();
        private final CountDownLatch bothOpened = new CountDownLatch(2);

        private BlockingOpenPlaybackSessionService(PlaybackSession first, PlaybackSession second) {
            responses.add(first);
            responses.add(second);
        }

        @Override
        public PlaybackSession openSession(PlaybackRequest request, String owner) {
            openRequests.add(request);
            PlaybackSession session = responses.remove();
            sessions.put(session.sessionId(), session);
            bothOpened.countDown();
            try {
                bothOpened.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            }
            return session;
        }
    }

    private record CloseRequest(
        String sessionId,
        String owner
    ) {
    }
}
