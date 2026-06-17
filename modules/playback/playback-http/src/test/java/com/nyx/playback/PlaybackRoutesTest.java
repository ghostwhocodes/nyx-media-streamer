package com.nyx.playback;

import static com.nyx.playback.PlaybackApiTestSupport.bodyAsText;
import static com.nyx.playback.PlaybackApiTestSupport.status;
import static com.nyx.playback.PlaybackContractFactories.audioSession;
import static com.nyx.playback.PlaybackContractFactories.mediaSessionTelemetry;
import static com.nyx.playback.PlaybackContractFactories.playbackDecision;
import static com.nyx.playback.PlaybackContractFactories.playbackSession;
import static com.nyx.playback.PlaybackContractFactories.playbackSessionArtifacts;
import static com.nyx.playback.PlaybackContractFactories.playbackSessionLifecycle;
import static com.nyx.playback.PlaybackContractFactories.playbackSourceAudioStream;
import static com.nyx.playback.PlaybackContractFactories.playbackSourceCharacteristics;
import static com.nyx.playback.PlaybackContractFactories.streamDescriptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.ErrorCode;
import com.nyx.common.ErrorDetail;
import com.nyx.common.ErrorResponse;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.common.RouteUtilsJava;
import com.nyx.common.VirtualPathResolver;
import com.nyx.common.DatabaseResources;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.ServerConfig;
import com.nyx.config.TranscodeConfig;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.http.AuthMode;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.Route;
import com.nyx.http.RoutingCall;
import com.nyx.http.TriConsumer;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import com.nyx.media.AudioMetadataService;
import com.nyx.media.MediaObjectResolver;
import com.nyx.media.MediaObjectService;
import com.nyx.media.MediaObjectUpsertRequest;
import com.nyx.media.UserMediaStateService;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObjectContracts;
import com.nyx.media.contracts.MediaObjectStatus;
import com.nyx.media.contracts.UserMediaStateWriteRequest;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.AudioSession;
import com.nyx.playback.contracts.AudioSessionService;
import com.nyx.playback.contracts.MediaSessionPlaybackEvent;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.nyx.playback.contracts.MediaSessionReportResult;
import com.nyx.playback.contracts.MediaSessionReportService;
import com.nyx.playback.contracts.PlaybackLifecycleEndReason;
import com.nyx.playback.contracts.PlaybackLifecyclePhase;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackReason;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSession;
import com.nyx.playback.contracts.PlaybackSessionService;
import com.nyx.playback.contracts.PlaybackSessionState;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaybackRoutesTest {
    private static final ObjectMapper JSON = NyxJson.newMapper();

    private Path tempDir;
    private Path moviesRoot;
    private final List<HikariDataSource> datasources = new ArrayList<>();
    private final List<ScheduledExecutorService> playbackDeliverySchedulers = new ArrayList<>();

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-stream-routes-test");
        moviesRoot = Files.createDirectories(tempDir.resolve("movies"));
    }

    @AfterEach
    void teardown() {
        datasources.forEach(HikariDataSource::close);
        datasources.clear();
        playbackDeliverySchedulers.forEach(ScheduledExecutorService::shutdownNow);
        playbackDeliverySchedulers.clear();
        deleteTree(tempDir);
    }

    @Test
    void openPlaybackSessionReturnsNegotiatedDecisionDetails() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("session-video.mkv"), new byte[100]);
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(readySession("session-open"));

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (Response response = harness.client().post("/api/v1/playback/sessions", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody(
                    """
                    {
                      "source":{"path":"movies/session-video.mkv"},
                      "clientProfile":{
                        "id":"mobile_hls",
                        "capabilities":{
                          "supportedContainers":["mp4"],
                          "supportedVideoCodecs":["h264"],
                          "supportedAudioCodecs":["aac"],
                          "supportedSubtitleFormats":["webvtt"]
                        },
                        "constraints":{
                          "video":{"maxWidth":1280,"maxHeight":720}
                        }
                      }
                    }
                    """
                );
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertEquals(moviesRoot.resolve("session-video.mkv").toString(), playbackService.openRequests.get(0).source().path());
                assertEquals("mobile_hls", playbackService.openRequests.get(0).clientProfile().id());
                JsonNode body = json(response);
                assertEquals("REMUX", body.get("decision").get("mode").asText());
                assertTrue(body.get("decision").toString().contains("ADAPTIVE_STREAMING_REQUESTED"));
            }
        });
    }

    @Test
    void openPlaybackSessionResolvesMediaObjectIdentity() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            MediaObjectSupport support = createMediaObjectSupport();
            Files.write(moviesRoot.resolve("identity-video.mkv"), new byte[100]);
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(readySession("session-identity"));

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, support.mediaObjectResolver());

            try (Response response = harness.client().post("/api/v1/playback/sessions", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody("{\"source\":{\"path\":\"movies/identity-video.mkv\"}}");
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                var forwarded = playbackService.openRequests.get(0).source();
                assertNotNull(forwarded.objectId());
                assertEquals(MediaKind.VIDEO, forwarded.mediaKind());
                boolean resolved = support.mediaObjectService().getByPath(moviesRoot.resolve("identity-video.mkv").toString()) != null;
                assertTrue(resolved);
            }
        });
    }

    @Test
    void openPlaybackSessionPreservesSourceCharacteristicsAfterPathResolution() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("characteristics-video.mkv"), new byte[100]);
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(readySession("session-characteristics"));

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (Response response = harness.client().post("/api/v1/playback/sessions", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody(
                    """
                    {
                      "source":{
                        "path":"movies/characteristics-video.mkv",
                        "characteristics":{
                          "container":"mkv",
                          "audioStreams":[{"index":1,"codec":"aac","channels":2,"bitrateKbps":192}]
                        }
                      }
                    }
                    """
                );
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                var forwarded = playbackService.openRequests.get(0).source();
                assertEquals(moviesRoot.resolve("characteristics-video.mkv").toString(), forwarded.path());
                assertEquals(
                    playbackSourceCharacteristics(
                        "mkv",
                        null,
                        null,
                        List.of(),
                        List.of(playbackSourceAudioStream(1, "aac", 2, 192, null, null, null)),
                        List.of()
                    ),
                    forwarded.characteristics()
                );
            }
        });
    }

    @Test
    void getPlaybackSessionExposesDecisionStateWithoutInspectingUrls() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            PlaybackSession session = readySession("session-get");
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(session);

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (Response response = harness.client().get("/api/v1/playback/sessions/" + session.sessionId())) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                JsonNode body = json(response);
                assertEquals("READY", body.get("state").asText());
                assertEquals("REMUX", body.get("decision").get("mode").asText());
                assertEquals("READY", body.get("lifecycle").get("phase").asText());
                assertEquals("true", body.get("lifecycle").get("canStop").asText());
                assertTrue(body.get("decision").toString().contains("ADAPTIVE_STREAMING_REQUESTED"));
            }
        });
    }

    @Test
    void explicitPlaybackSessionObservationDoesNotRegisterSimpleStreamIdleCleanup() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            PlaybackSession session = readySession("session-explicit");
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(session);

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (
                AutoCloseable ignored = PlaybackRoutes.useSimpleStreamIdleCleanupForTesting(25L);
                Response response = harness.client().get("/api/v1/playback/sessions/" + session.sessionId())
            ) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                JsonNode body = json(response);
                assertEquals("session-explicit", body.path("sessionId").asText());
            }

            Thread.sleep(75L);
            assertTrue(playbackService.closeRequests.isEmpty());
        });
    }

    @Test
    void primaryMediaReportRouteAcceptsPlaybackSessionPayloads() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            PlaybackSession baseSession = readySession("session-report");
            PlaybackSession reportedSession = playbackSession(
                baseSession.sessionId(),
                "object-video-route-1",
                MediaKind.VIDEO,
                baseSession.state(),
                baseSession.decision(),
                baseSession.artifacts(),
                mediaSessionTelemetry(
                    "object-video-route-1",
                    MediaKind.VIDEO,
                    MediaSessionPlaybackEvent.HEARTBEAT,
                    null,
                    45_000L,
                    180_000L,
                    25.0,
                    null,
                    null,
                    null
                ),
                baseSession.failureCode(),
                baseSession.failureMessage(),
                baseSession.createdAt(),
                baseSession.lifecycle()
            );
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(reportedSession, reportedSession, null);
            StubMediaSessionReportService mediaSessionReportService = new StubMediaSessionReportService(
                new MediaSessionReportResult.Playback(reportedSession)
            );

            installPlaybackRoutes(harness, playbackService, mediaSessionReportService, List.of(), null, null);

            try (Response response = harness.client().post("/api/v1/media/sessions/session-report/report", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody(
                    """
                    {
                      "event":"HEARTBEAT",
                      "objectId":"object-video-route-1",
                      "mediaKind":"VIDEO",
                      "positionMillis":45000,
                      "durationMillis":180000
                    }
                    """
                );
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertTrue(playbackService.reportRequests.isEmpty());
                assertEquals("object-video-route-1", mediaSessionReportService.reportRequests.get(0).report().objectId());
                assertEquals(MediaKind.VIDEO, mediaSessionReportService.reportRequests.get(0).report().mediaKind());
                JsonNode body = json(response);
                assertEquals("object-video-route-1", body.get("objectId").asText());
                assertEquals("VIDEO", body.get("mediaKind").asText());
                assertEquals("HEARTBEAT", body.get("telemetry").get("lastEvent").asText());
            }
        });
    }

    @Test
    void primaryMediaReportRouteRespondsWithSharedServiceResults() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            AudioSession reportedSession = audioSession(
                "media-report",
                "object-audio-route-1",
                MediaKind.AUDIO,
                PlaybackSessionState.READY,
                null,
                null,
                null,
                mediaSessionTelemetry(
                    "object-audio-route-1",
                    MediaKind.AUDIO,
                    MediaSessionPlaybackEvent.COMPLETED,
                    null,
                    null,
                    null,
                    100.0,
                    null,
                    null,
                    null
                ),
                null,
                null,
                "2026-04-09T12:00:00Z",
                null
            );
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(readySession("fallback-session"));
            StubMediaSessionReportService mediaSessionReportService = new StubMediaSessionReportService(
                new MediaSessionReportResult.Audio(reportedSession)
            );

            installPlaybackRoutes(harness, playbackService, mediaSessionReportService, List.of(), null, null);

            try (Response response = harness.client().post("/api/v1/media/sessions/media-report/report", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody("{\"event\":\"COMPLETED\"}");
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertEquals("media-report", mediaSessionReportService.reportRequests.get(0).sessionId());
                JsonNode body = json(response);
                assertEquals("object-audio-route-1", body.get("objectId").asText());
                assertEquals("AUDIO", body.get("mediaKind").asText());
                assertEquals("COMPLETED", body.get("telemetry").get("lastEvent").asText());
            }
        });
    }

    @Test
    void authenticatedPrimaryMediaReportRouteProjectsDurableVideoPlaystate() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            MediaObjectSupport support = createMediaObjectSupport();
            Path videoPath = moviesRoot.resolve("projected-video.mp4");
            Files.write(videoPath, new byte[100]);
            String objectId = createMediaObject(support.mediaObjectService(), videoPath, MediaKind.VIDEO);
            support.userMediaStateService().putState(
                "alice",
                objectId,
                new UserMediaStateWriteRequest(null, false, true, 9)
            );
            PlaybackSession baseSession = readySession("projected-video-session");
            PlaybackSession session = playbackSession(
                baseSession.sessionId(),
                objectId,
                MediaKind.VIDEO,
                baseSession.state(),
                baseSession.decision(),
                baseSession.artifacts(),
                baseSession.telemetry(),
                baseSession.failureCode(),
                baseSession.failureMessage(),
                baseSession.createdAt(),
                baseSession.lifecycle()
            );
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(
                session,
                session,
                report -> playbackSession(
                    session.sessionId(),
                    session.objectId(),
                    session.mediaKind(),
                    report.event() == MediaSessionPlaybackEvent.COMPLETED
                        ? PlaybackSessionState.CLOSED
                        : PlaybackSessionState.READY,
                    session.decision(),
                    session.artifacts(),
                    mediaSessionTelemetry(
                        objectId,
                        MediaKind.VIDEO,
                        report.event(),
                        null,
                        report.positionMillis(),
                        report.durationMillis(),
                        report.event() == MediaSessionPlaybackEvent.COMPLETED ? 100.0 : 60.0,
                        null,
                        null,
                        null
                    ),
                    session.failureCode(),
                    session.failureMessage(),
                    session.createdAt(),
                    session.lifecycle()
                )
            );
            LocalMediaSessionReportService mediaSessionReportService = new LocalMediaSessionReportService(
                new MissingAudioSessionService(),
                playbackService,
                support.userMediaStateService()
            );

            installAuthenticatedPlaybackRoutes(harness, playbackService, mediaSessionReportService, "test-token");

            try (Response heartbeatResponse = harness.client().post("/api/v1/media/sessions/" + session.sessionId() + "/report", request -> {
                request.contentType(ContentType.Application.Json);
                request.header(HttpHeaders.Authorization, "Bearer test-token");
                request.setBody("{\"event\":\"HEARTBEAT\",\"positionMillis\":60000,\"durationMillis\":100000}");
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(heartbeatResponse));
            }

            var heartbeatState = support.userMediaStateService().getState("alice", objectId);
            assertEquals(60_000L, heartbeatState.resumePositionMillis());
            assertFalse(heartbeatState.watched());
            assertTrue(heartbeatState.favorite());
            assertEquals(9, heartbeatState.rating());

            try (Response completedResponse = harness.client().post("/api/v1/media/sessions/" + session.sessionId() + "/report", request -> {
                request.contentType(ContentType.Application.Json);
                request.header(HttpHeaders.Authorization, "Bearer test-token");
                request.setBody("{\"event\":\"COMPLETED\"}");
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(completedResponse));
            }

            var completedState = support.userMediaStateService().getState("alice", objectId);
            assertTrue(completedState.watched());
            assertNull(completedState.resumePositionMillis());
            assertEquals(1, completedState.playCount());
            assertTrue(completedState.favorite());
            assertEquals(9, completedState.rating());
        });
    }

    @Test
    void deletePlaybackSessionLeavesDurablePlaystateUnchanged() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            MediaObjectSupport support = createMediaObjectSupport();
            Path videoPath = moviesRoot.resolve("delete-video.mp4");
            Files.write(videoPath, new byte[100]);
            String objectId = createMediaObject(support.mediaObjectService(), videoPath, MediaKind.VIDEO);
            support.userMediaStateService().putState(
                "alice",
                objectId,
                new UserMediaStateWriteRequest(12_000L, false, true, 7)
            );
            PlaybackSession baseSession = readySession("delete-video-session");
            PlaybackSession session = playbackSession(
                baseSession.sessionId(),
                objectId,
                MediaKind.VIDEO,
                baseSession.state(),
                baseSession.decision(),
                baseSession.artifacts(),
                baseSession.telemetry(),
                baseSession.failureCode(),
                baseSession.failureMessage(),
                baseSession.createdAt(),
                baseSession.lifecycle()
            );
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(session);
            StubMediaSessionReportService mediaSessionReportService = new StubMediaSessionReportService(
                new MediaSessionReportResult.Playback(session)
            );

            installAuthenticatedPlaybackRoutes(harness, playbackService, mediaSessionReportService, "test-token");

            var beforeDelete = support.userMediaStateService().getState("alice", objectId);

            try (Response deleteResponse = harness.client().delete("/api/v1/playback/sessions/" + session.sessionId(), request ->
                request.header(HttpHeaders.Authorization, "Bearer test-token")
            )) {
                assertEquals(HttpStatusCode.Companion.getNoContent(), status(deleteResponse));
            }
            assertEquals(List.of(new CloseRequest(session.sessionId(), "alice")), playbackService.closeRequests);
            assertTrue(mediaSessionReportService.reportRequests.isEmpty());

            var afterDelete = support.userMediaStateService().getState("alice", objectId);
            assertEquals(beforeDelete.resumePositionMillis(), afterDelete.resumePositionMillis());
            assertEquals(beforeDelete.watched(), afterDelete.watched());
            assertEquals(beforeDelete.playCount(), afterDelete.playCount());
            assertEquals(beforeDelete.favorite(), afterDelete.favorite());
            assertEquals(beforeDelete.rating(), afterDelete.rating());
        });
    }

    @Test
    void directContentReturnsNotReadyForFailedSession() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(failedSession("session-direct-failed"));

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (Response response = harness.client().get("/api/v1/playback/sessions/session-direct-failed/content")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), status(response));
                assertTrue(bodyAsText(response).contains("Playback session is not ready for direct content"));
            }
        });
    }

    @Test
    void directContentReturnsNotReadyForStoppedSession() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(stoppedSession("session-direct-stopped"));

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (Response response = harness.client().get("/api/v1/playback/sessions/session-direct-stopped/content")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), status(response));
                assertTrue(bodyAsText(response).contains("Playback session is not ready for direct content"));
            }
        });
    }

    @Test
    void directContentReturnsNotFoundForMissingSession() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(readySession("session-direct-other"));

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (Response response = harness.client().get("/api/v1/playback/sessions/session-direct-missing/content")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), status(response));
                assertTrue(bodyAsText(response).contains("Playback session not found"));
            }
        });
    }

    @Test
    void streamRouteStopsPollingWhenPlaybackSessionHasAlreadyFailed() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("failed-session-video.mkv"), new byte[100]);
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(failedSession("session-failed"));

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (Response response = harness.client().get("/api/v1/stream.m3u8?path=movies/failed-session-video.mkv&quality=medium")) {
                assertEquals(HttpStatusCode.Companion.getInternalServerError(), status(response));
                assertTrue(bodyAsText(response).contains("Playback session failed"));
            }
        });
    }

    @Test
    void streamRouteReturnsReadyManifestImmediately() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("ready-session-video.mkv"), new byte[100]);
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(
                readySession("session-ready"),
                "#EXTM3U\n#EXT-X-VERSION:7\n"
            );

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (Response response = harness.client().get(
                "/api/v1/stream.m3u8?path=movies/ready-session-video.mkv&quality=high"
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertEquals("session-ready", response.header("X-Nyx-Playback-Session-Id"));
                assertTrue(bodyAsText(response).contains("#EXTM3U"));
            }

            assertEquals(1, playbackService.openRequests.size());
            assertEquals("h265_quality", playbackService.openRequests.getFirst().transcode().profileHint());
        });
    }

    @Test
    void streamRouteCleansUpImmediateReadyManifestSessionAfterIdleTtl() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("ready-cleanup-video.mkv"), new byte[100]);
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(
                readySession("session-ready-cleanup"),
                "#EXTM3U\n#EXT-X-VERSION:7\n"
            );

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (
                AutoCloseable ignored = PlaybackRoutes.useSimpleStreamIdleCleanupForTesting(25L);
                Response response = harness.client().get(
                    "/api/v1/stream.m3u8?path=movies/ready-cleanup-video.mkv&quality=medium"
                )
            ) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertEquals("session-ready-cleanup", response.header("X-Nyx-Playback-Session-Id"));
            }

            assertEventually(() -> playbackService.closeRequests.contains(new CloseRequest("session-ready-cleanup", null)));
        });
    }

    @Test
    void streamRouteReturnsRetryMetadataWhenManifestIsStillPending() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("pending-session-video.mkv"), new byte[100]);
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(startingSession("session-pending"));

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (
                AutoCloseable ignored = PlaybackRoutes.useManifestPollingForTesting(1, 0L);
                Response response = harness.client().get(
                    "/api/v1/stream.m3u8?path=movies/pending-session-video.mkv&quality=medium"
                )
            ) {
                assertEquals(HttpStatusCode.Companion.getAccepted(), status(response));
                assertEquals("2", response.header(HttpHeaders.RetryAfter));
                JsonNode body = json(response);
                assertEquals("session-pending", body.path("sessionId").asText());
                assertEquals("/api/v1/playback/sessions/session-pending/master.m3u8", body.path("playbackUrl").asText());
                assertEquals("pending", body.path("status").asText());
            }

            assertEquals(1, playbackService.openRequests.size());
        });
    }

    @Test
    void streamRouteCleansUpPendingSessionThatBecomesReadyDuringStartupPoll() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("pending-ready-cleanup-video.mkv"), new byte[100]);
            class PendingReadyPlaybackSessionService extends StubPlaybackSessionService {
                private PendingReadyPlaybackSessionService() {
                    super(startingSession("session-pending-ready-cleanup"), "#EXTM3U\n#EXT-X-VERSION:7\n");
                }

                @Override
                public PlaybackSession getSession(String sessionId, String owner) {
                    return "session-pending-ready-cleanup".equals(sessionId) ? readySession(sessionId) : null;
                }
            }
            StubPlaybackSessionService playbackService = new PendingReadyPlaybackSessionService();

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (
                AutoCloseable polling = PlaybackRoutes.useManifestPollingForTesting(1, 0L);
                AutoCloseable cleanup = PlaybackRoutes.useSimpleStreamIdleCleanupForTesting(25L);
                Response response = harness.client().get(
                    "/api/v1/stream.m3u8?path=movies/pending-ready-cleanup-video.mkv&quality=medium"
                )
            ) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertEquals("session-pending-ready-cleanup", response.header("X-Nyx-Playback-Session-Id"));
                assertTrue(bodyAsText(response).contains("#EXTM3U"));
            }

            assertEventually(() -> playbackService.closeRequests.contains(new CloseRequest("session-pending-ready-cleanup", null)));
        });
    }

    @Test
    void streamRouteCleansUpImmediateDirectFileSessionAfterIdleTtl() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Path movie = moviesRoot.resolve("direct-cleanup-video.mp4");
            Files.writeString(movie, "direct file");
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(
                fileReadySession("session-direct-cleanup"),
                fileReadySession("session-direct-cleanup"),
                null,
                null,
                movie
            );

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (
                AutoCloseable ignored = PlaybackRoutes.useSimpleStreamIdleCleanupForTesting(25L);
                Response response = harness.client().get(
                    "/api/v1/stream.m3u8?path=movies/direct-cleanup-video.mp4&quality=passthrough"
                )
            ) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertEquals("session-direct-cleanup", response.header("X-Nyx-Playback-Session-Id"));
                assertEquals("direct file", bodyAsText(response));
            }

            assertEventually(() -> playbackService.closeRequests.contains(new CloseRequest("session-direct-cleanup", null)));
        });
    }

    @Test
    void streamRouteReusesImmediateDirectFileSessionForRangeRetriesWhileIdleLeaseIsActive() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Path movie = moviesRoot.resolve("direct-retry-video.mp4");
            Files.writeString(movie, "direct retry file");
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(
                fileReadySession("session-direct-retry"),
                fileReadySession("session-direct-retry"),
                null,
                null,
                movie
            );

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            String url = "/api/v1/stream.m3u8?path=movies/direct-retry-video.mp4&quality=passthrough";
            try (Response first = harness.client().get(url)) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(first));
                assertEquals("session-direct-retry", first.header("X-Nyx-Playback-Session-Id"));
                assertEquals("direct retry file", bodyAsText(first));
            }

            try (Response retry = harness.client().get(url, request -> request.header(HttpHeaders.Range, "bytes=0-5"))) {
                assertEquals(HttpStatusCode.Companion.getPartialContent(), status(retry));
                assertEquals("session-direct-retry", retry.header("X-Nyx-Playback-Session-Id"));
                assertEquals("direct", bodyAsText(retry));
            }

            assertEquals(1, playbackService.openRequests.size());
            assertTrue(playbackService.closeRequests.isEmpty());
        });
    }

    @Test
    void unauthorizedSimpleStreamProbeDoesNotClearOwnersIdleLease() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Path movie = moviesRoot.resolve("direct-owner-video.mp4");
            Files.writeString(movie, "direct owner file");
            class OwnerScopedPlaybackSessionService extends StubPlaybackSessionService {
                private OwnerScopedPlaybackSessionService() {
                    super(
                        fileReadySession("session-direct-owner"),
                        fileReadySession("session-direct-owner"),
                        null,
                        null,
                        movie
                    );
                }

                @Override
                public PlaybackSession getSession(String sessionId, String owner) {
                    if (!"alice".equals(owner)) {
                        return null;
                    }
                    return super.getSession(sessionId, owner);
                }
            }
            StubPlaybackSessionService playbackService = new OwnerScopedPlaybackSessionService();

            installAuthenticatedPlaybackRoutes(harness, playbackService, null, "test-token");

            try (AutoCloseable ignored = PlaybackRoutes.useSimpleStreamIdleCleanupForTesting(25L)) {
                try (Response stream = harness.client().get(
                    "/api/v1/stream.m3u8?path=movies/direct-owner-video.mp4&quality=passthrough",
                    request -> request.header(HttpHeaders.Authorization, "Bearer test-token")
                )) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(stream));
                    assertEquals("session-direct-owner", stream.header("X-Nyx-Playback-Session-Id"));
                }

                try (Response probe = harness.client().get(
                    "/api/v1/playback/sessions/session-direct-owner",
                    request -> request.header(HttpHeaders.Authorization, "Bearer other-token")
                )) {
                    assertEquals(HttpStatusCode.Companion.getNotFound(), status(probe));
                }
            }

            assertEventually(() -> playbackService.closeRequests.contains(new CloseRequest("session-direct-owner", "alice")));
        });
    }

    @Test
    void streamRouteReusesPendingSessionWhenOriginalRouteIsRetried() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("retry-pending-video.mkv"), new byte[100]);
            StubPlaybackSessionService playbackService = new StubPlaybackSessionService(startingSession("session-retry-pending"));

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (AutoCloseable ignored = PlaybackRoutes.useManifestPollingForTesting(1, 0L)) {
                String url = "/api/v1/stream.m3u8?path=movies/retry-pending-video.mkv&quality=medium";
                try (Response first = harness.client().get(url)) {
                    assertEquals(HttpStatusCode.Companion.getAccepted(), status(first));
                    JsonNode body = json(first);
                    assertEquals("session-retry-pending", body.path("sessionId").asText());
                    assertEquals("/api/v1/playback/sessions/session-retry-pending/master.m3u8", body.path("playbackUrl").asText());
                }

                try (Response retry = harness.client().get(url)) {
                    assertEquals(HttpStatusCode.Companion.getAccepted(), status(retry));
                    JsonNode body = json(retry);
                    assertEquals("session-retry-pending", body.path("sessionId").asText());
                    assertEquals("/api/v1/playback/sessions/session-retry-pending/master.m3u8", body.path("playbackUrl").asText());
                }
            }

            assertEquals(1, playbackService.openRequests.size());
        });
    }

    @Test
    void streamRouteClearsPendingRetryCacheWhenClientPollsReturnedPlaybackUrl() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("follow-playback-url-video.mkv"), new byte[100]);
            class FollowPlaybackUrlSessionService implements PlaybackSessionService {
                private final List<PlaybackRequest> openRequests = new ArrayList<>();
                private int sequence = 0;
                private int getSessionCalls = 0;

                @Override
                public PlaybackSession openSession(PlaybackRequest request, String owner) {
                    openRequests.add(request);
                    sequence += 1;
                    return startingSession("session-follow-" + sequence);
                }

                @Override
                public PlaybackSession getSession(String sessionId, String owner) {
                    getSessionCalls += 1;
                    return getSessionCalls == 1 ? startingSession(sessionId) : readySession(sessionId);
                }

                @Override
                public PlaybackSession reportPlayback(
                    String sessionId,
                    MediaSessionPlaybackReport report,
                    String owner
                ) {
                    return readySession(sessionId);
                }

                @Override
                public String getSessionJobId(String sessionId, String owner) {
                    return null;
                }

                @Override
                public void closeSession(String sessionId, String owner) {
                }

                @Override
                public String getHlsManifest(String sessionId, String owner) {
                    return "#EXTM3U\n# " + sessionId + "\n";
                }

                @Override
                public String getDashManifest(String sessionId, String owner) {
                    return null;
                }

                @Override
                public Path getDirectContentPath(String sessionId, String owner) {
                    return null;
                }
            }
            FollowPlaybackUrlSessionService playbackService = new FollowPlaybackUrlSessionService();

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (AutoCloseable ignored = PlaybackRoutes.useManifestPollingForTesting(1, 0L)) {
                String streamUrl = "/api/v1/stream.m3u8?path=movies/follow-playback-url-video.mkv&quality=medium";
                String playbackUrl;
                try (Response pending = harness.client().get(streamUrl)) {
                    assertEquals(HttpStatusCode.Companion.getAccepted(), status(pending));
                    JsonNode body = json(pending);
                    assertEquals("session-follow-1", body.path("sessionId").asText());
                    playbackUrl = body.path("playbackUrl").asText();
                }

                try (Response playbackPoll = harness.client().get(playbackUrl)) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(playbackPoll));
                    assertTrue(bodyAsText(playbackPoll).contains("session-follow-1"));
                }

                try (Response retriedOriginalRoute = harness.client().get(streamUrl)) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(retriedOriginalRoute));
                    assertTrue(bodyAsText(retriedOriginalRoute).contains("session-follow-2"));
                }
            }

            assertEquals(2, playbackService.openRequests.size());
        });
    }

    @Test
    void streamRouteClearsPendingRetryCacheWhenSessionBecomesReadyDuringStartupPoll() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("transitioning-video.mkv"), new byte[100]);
            class TransitioningPlaybackSessionService implements PlaybackSessionService {
                private final List<PlaybackRequest> openRequests = new ArrayList<>();
                private int sequence = 0;

                @Override
                public PlaybackSession openSession(PlaybackRequest request, String owner) {
                    openRequests.add(request);
                    sequence += 1;
                    return startingSession("session-transition-" + sequence);
                }

                @Override
                public PlaybackSession getSession(String sessionId, String owner) {
                    return readySession(sessionId);
                }

                @Override
                public PlaybackSession reportPlayback(
                    String sessionId,
                    MediaSessionPlaybackReport report,
                    String owner
                ) {
                    return readySession(sessionId);
                }

                @Override
                public String getSessionJobId(String sessionId, String owner) {
                    return null;
                }

                @Override
                public void closeSession(String sessionId, String owner) {
                }

                @Override
                public String getHlsManifest(String sessionId, String owner) {
                    return "#EXTM3U\n#EXT-X-VERSION:7\n";
                }

                @Override
                public String getDashManifest(String sessionId, String owner) {
                    return null;
                }

                @Override
                public Path getDirectContentPath(String sessionId, String owner) {
                    return null;
                }
            }
            TransitioningPlaybackSessionService playbackService = new TransitioningPlaybackSessionService();

            installPlaybackRoutes(harness, playbackService, null, List.of(), null, null);

            try (AutoCloseable ignored = PlaybackRoutes.useManifestPollingForTesting(1, 0L)) {
                String url = "/api/v1/stream.m3u8?path=movies/transitioning-video.mkv&quality=medium";
                try (Response first = harness.client().get(url)) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(first));
                    assertTrue(bodyAsText(first).contains("#EXTM3U"));
                }

                try (Response second = harness.client().get(url)) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(second));
                    assertTrue(bodyAsText(second).contains("#EXTM3U"));
                }
            }

            assertEquals(2, playbackService.openRequests.size());
        });
    }

    @Test
    void streamWithoutPathReturns400() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Route route = harness.route();
            route.get("/api/v1/stream.m3u8", scope -> {
                RoutingCall call = scope.getCall();
                String path = call.getQueryParameters().get("path");
                if (path == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }
                String quality = call.getQueryParameters().get("quality");
                if (quality == null) {
                    quality = "medium";
                }

                RouteUtilsJava.resolvePathParam(path, createPathSecurity(), createResolver());

                switch (quality.toLowerCase(Locale.ROOT)) {
                    case "low", "medium", "high", "passthrough" -> {
                    }
                    default -> throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid quality: " + quality);
                }

                call.respondText("OK");
            });

            try (Response response = harness.client().get("/api/v1/stream.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), status(response));
            }
        });
    }

    @Test
    void streamWithUnknownRootReturns404() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Route route = harness.route();
            route.get("/api/v1/stream.m3u8", scope -> {
                RoutingCall call = scope.getCall();
                String path = call.getQueryParameters().get("path");
                if (path == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }
                RouteUtilsJava.resolvePathParam(path, createPathSecurity(), createResolver());
                call.respondText("OK");
            });

            try (Response response = harness.client().get("/api/v1/stream.m3u8?path=nonexistent/video.mp4")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), status(response));
            }
        });
    }

    @Test
    void streamWithInvalidQualityReturns400() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("test.mp4"), new byte[100]);
            Map<String, String> presets = Map.of(
                "low", "h264_fast",
                "medium", "h264_balanced",
                "high", "h265_quality"
            );

            Route route = harness.route();
            route.get("/api/v1/stream.m3u8", scope -> {
                RoutingCall call = scope.getCall();
                String path = call.getQueryParameters().get("path");
                if (path == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }
                String quality = call.getQueryParameters().get("quality");
                if (quality == null) {
                    quality = "medium";
                }

                RouteUtilsJava.resolvePathParam(path, createPathSecurity(), createResolver());

                String normalizedQuality = quality.toLowerCase(Locale.ROOT);
                if (!"passthrough".equals(normalizedQuality) && !presets.containsKey(normalizedQuality)) {
                    throw nyxException(
                        ErrorCode.INVALID_REQUEST,
                        "Invalid quality '" + quality + "'. Available: passthrough, " + String.join(", ", Set.copyOf(presets.keySet()).stream().sorted().toList())
                    );
                }

                call.respondText("OK");
            });

            try (Response response = harness.client().get("/api/v1/stream.m3u8?path=movies/test.mp4&quality=ultraHD")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), status(response));
                String body = bodyAsText(response);
                assertTrue(body.contains("Available"), "Error body should mention available presets: " + body);
                assertTrue(
                    body.contains("high") || body.contains("low") || body.contains("medium"),
                    "Error body should list preset names: " + body
                );
            }
        });
    }

    @Test
    void streamWithValidPathAndQualityResolvesCorrectly() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("video.mp4"), new byte[100]);

            Route route = harness.route();
            route.get("/api/v1/stream.m3u8", scope -> {
                RoutingCall call = scope.getCall();
                String path = call.getQueryParameters().get("path");
                if (path == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }
                String quality = call.getQueryParameters().get("quality");
                if (quality == null) {
                    quality = "medium";
                }

                RouteUtilsJava.resolvePathParam(path, createPathSecurity(), createResolver());

                String profile = switch (quality.toLowerCase(Locale.ROOT)) {
                    case "low" -> "h264_fast";
                    case "medium" -> "h264_balanced";
                    case "high" -> "h265_quality";
                    case "passthrough" -> null;
                    default -> throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid quality: " + quality);
                };

                call.respondText("profile=" + profile, ContentType.Text.Plain);
            });

            try (Response response = harness.client().get("/api/v1/stream.m3u8?path=movies/video.mp4&quality=low")) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertEquals("profile=h264_fast", bodyAsText(response));
            }
        });
    }

    @Test
    void streamPassthroughQualityMapsToNullProfile() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Files.write(moviesRoot.resolve("video.mp4"), new byte[100]);

            Route route = harness.route();
            route.get("/api/v1/stream.m3u8", scope -> {
                RoutingCall call = scope.getCall();
                String path = call.getQueryParameters().get("path");
                if (path == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }
                String quality = call.getQueryParameters().get("quality");
                if (quality == null) {
                    quality = "medium";
                }

                RouteUtilsJava.resolvePathParam(path, createPathSecurity(), createResolver());

                String profile = switch (quality.toLowerCase(Locale.ROOT)) {
                    case "low" -> "h264_fast";
                    case "medium" -> "h264_balanced";
                    case "high" -> "h265_quality";
                    case "passthrough" -> null;
                    default -> throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid quality: " + quality);
                };

                call.respondText("profile=" + (profile == null ? "passthrough" : profile), ContentType.Text.Plain);
            });

            try (Response response = harness.client().get("/api/v1/stream.m3u8?path=movies/video.mp4&quality=passthrough")) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertEquals("profile=passthrough", bodyAsText(response));
            }
        });
    }

    private ServerConfig testServerConfig() {
        return new ServerConfig(
            "0.0.0.0",
            8080,
            List.of(),
            List.of(new MediaRootConfig(moviesRoot)),
            new FfmpegConfig("ffmpeg", "ffprobe", "6.0", 2),
            new TranscodeConfig("both", 10, 6),
            new DatabaseConfig(tempDir.resolve("db"))
        );
    }

    private VirtualPathResolver createResolver() {
        return new VirtualPathResolver(List.of(new MediaRootConfig(moviesRoot)));
    }

    private PathSecurity createPathSecurity() {
        return new PathSecurity(List.of(moviesRoot));
    }

    private MediaObjectSupport createMediaObjectSupport() {
        ProbeService probeService = new ProbeService();
        AudioMetadataService audioMetadataService = new AudioMetadataService(probeService);
        DatabaseResources databaseResources = MediaObjectService.createDatabase(tempDir.resolve("media-objects-db"));
        datasources.add(databaseResources.getDataSource());
        MediaObjectService mediaObjectService = new MediaObjectService(databaseResources.getJdbi());
        return new MediaObjectSupport(
            mediaObjectService,
            new MediaObjectResolver(mediaObjectService, probeService, audioMetadataService),
            new UserMediaStateService(databaseResources.getJdbi())
        );
    }

    private String createMediaObject(MediaObjectService mediaObjectService, Path path, MediaKind mediaKind) throws IOException {
        return mediaObjectService.upsertPrimaryPath(
            new MediaObjectUpsertRequest(
                mediaKind,
                path.toString(),
                "video/mp4",
                Files.size(path),
                "2026-04-12T12:00:00Z",
                path.getFileName().toString(),
                100_000L,
                1920,
                1080,
                null,
                null,
                null,
                null,
                null,
                MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
                null,
                MediaObjectStatus.ACTIVE
            )
        ).objectId();
    }

    private void installPlaybackRoutes(
        PlaybackApiTestSupport.ApplicationHarness harness,
        PlaybackSessionService playbackSessionService,
        MediaSessionReportService mediaSessionReportService,
        List<String> authProviders,
        String token,
        MediaObjectResolver mediaObjectResolver
    ) {
        Route route = authProviders.isEmpty() ? harness.route() : harness.route(authEvaluator(token));
        ScheduledExecutorService playbackDeliveryScheduler = Executors.newSingleThreadScheduledExecutor();
        playbackDeliverySchedulers.add(playbackDeliveryScheduler);
        PlaybackRoutes.playbackRoutes(
            route,
            playbackSessionService,
            new LocalPlaybackDeliveryService(playbackSessionService, playbackDeliveryScheduler),
            mediaSessionReportService,
            createPathSecurity(),
            createResolver(),
            testServerConfig(),
            authProviders,
            null,
            mediaObjectResolver
        );
    }

    private void installAuthenticatedPlaybackRoutes(
        PlaybackApiTestSupport.ApplicationHarness harness,
        PlaybackSessionService playbackSessionService,
        MediaSessionReportService mediaSessionReportService,
        String token
    ) {
        installPlaybackRoutes(
            harness,
            playbackSessionService,
            mediaSessionReportService,
            List.of("api-token"),
            token,
            null
        );
    }

    private TriConsumer<? super RoutingCall, ? super AuthMode, ? super List<String>> authEvaluator(String token) {
        return (call, authMode, authProviders) -> {
            if (authMode == AuthMode.PUBLIC || authProviders.isEmpty()) {
                return;
            }

            String authorization = call.getRequest().getHeaders().get(HttpHeaders.Authorization);
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String presented = authorization.substring(7).trim();
                if (token.equals(presented)) {
                    call.attribute(Route.AUTH_PRINCIPAL_ATTRIBUTE, new UserIdPrincipal("alice"));
                    return;
                }
                if ("other-token".equals(presented)) {
                    call.attribute(Route.AUTH_PRINCIPAL_ATTRIBUTE, new UserIdPrincipal("bob"));
                    return;
                }
            }

            call.respond(
                HttpStatusCode.Companion.getUnauthorized(),
                new ErrorResponse(new ErrorDetail("UNAUTHORIZED", "Authentication required"))
            );
            call.abort();
        };
    }

    private PlaybackSession readySession(String sessionId) {
        return playbackSession(
            sessionId,
            "object-" + sessionId,
            MediaKind.VIDEO,
            PlaybackSessionState.READY,
            playbackDecision(
                PlaybackMode.REMUX,
                streamDescriptor(StreamingProtocol.HLS, "fmp4", true),
                Set.of(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED),
                true,
                true
            ),
            playbackSessionArtifacts(
                StreamingProtocol.HLS,
                "/api/v1/playback/sessions/" + sessionId + "/master.m3u8",
                null,
                "/api/v1/playback/sessions/" + sessionId + "/master.m3u8",
                null
            ),
            mediaSessionTelemetry(
                "object-" + sessionId,
                MediaKind.VIDEO,
                null,
                null,
                null,
                null,
                0.0,
                null,
                null,
                null
            ),
            null,
            null,
            "2026-04-09T12:00:00Z",
            playbackSessionLifecycle(
                PlaybackLifecyclePhase.READY,
                "2026-04-09T12:00:00Z",
                "2026-04-09T12:00:02Z",
                null,
                100.0,
                true,
                null
            )
        );
    }

    private PlaybackSession fileReadySession(String sessionId) {
        return playbackSession(
            sessionId,
            "object-" + sessionId,
            MediaKind.VIDEO,
            PlaybackSessionState.READY,
            playbackDecision(
                PlaybackMode.DIRECT_PLAY,
                streamDescriptor(StreamingProtocol.FILE, "file", false),
                Set.of(),
                true,
                false
            ),
            playbackSessionArtifacts(
                StreamingProtocol.FILE,
                "/api/v1/playback/sessions/" + sessionId + "/content",
                "/api/v1/playback/sessions/" + sessionId + "/content",
                null,
                null
            ),
            mediaSessionTelemetry(
                "object-" + sessionId,
                MediaKind.VIDEO,
                null,
                null,
                null,
                null,
                0.0,
                null,
                null,
                null
            ),
            null,
            null,
            "2026-04-09T12:00:00Z",
            playbackSessionLifecycle(
                PlaybackLifecyclePhase.READY,
                "2026-04-09T12:00:00Z",
                "2026-04-09T12:00:00Z",
                null,
                100.0,
                true,
                null
            )
        );
    }

    private PlaybackSession startingSession(String sessionId) {
        return playbackSession(
            sessionId,
            "object-" + sessionId,
            MediaKind.VIDEO,
            PlaybackSessionState.PENDING,
            playbackDecision(
                PlaybackMode.REMUX,
                streamDescriptor(StreamingProtocol.HLS, "fmp4", true),
                Set.of(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED),
                true,
                true
            ),
            playbackSessionArtifacts(
                StreamingProtocol.HLS,
                "/api/v1/playback/sessions/" + sessionId + "/master.m3u8",
                null,
                "/api/v1/playback/sessions/" + sessionId + "/master.m3u8",
                null
            ),
            mediaSessionTelemetry(
                "object-" + sessionId,
                MediaKind.VIDEO,
                null,
                null,
                null,
                null,
                0.0,
                null,
                null,
                null
            ),
            null,
            null,
            "2026-04-09T12:00:00Z",
            playbackSessionLifecycle(
                PlaybackLifecyclePhase.STARTING,
                "2026-04-09T12:00:00Z",
                null,
                null,
                10.0,
                false,
                null
            )
        );
    }

    private PlaybackSession failedSession(String sessionId) {
        return playbackSession(
            sessionId,
            "object-" + sessionId,
            MediaKind.VIDEO,
            PlaybackSessionState.FAILED,
            playbackDecision(
                PlaybackMode.REMUX,
                streamDescriptor(StreamingProtocol.HLS, "fmp4", true),
                Set.of(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED),
                true,
                true
            ),
            playbackSessionArtifacts(
                StreamingProtocol.HLS,
                "/api/v1/playback/sessions/" + sessionId + "/master.m3u8",
                null,
                "/api/v1/playback/sessions/" + sessionId + "/master.m3u8",
                null
            ),
            null,
            null,
            "Playback session failed",
            "2026-04-09T12:00:00Z",
            playbackSessionLifecycle(
                PlaybackLifecyclePhase.FAILED,
                "2026-04-09T12:00:00Z",
                null,
                "2026-04-09T12:00:03Z",
                null,
                false,
                PlaybackLifecycleEndReason.BACKING_JOB_FAILED
            )
        );
    }

    private PlaybackSession stoppedSession(String sessionId) {
        return playbackSession(
            sessionId,
            "object-" + sessionId,
            MediaKind.VIDEO,
            PlaybackSessionState.CLOSED,
            playbackDecision(
                PlaybackMode.REMUX,
                streamDescriptor(StreamingProtocol.HLS, "fmp4", true),
                Set.of(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED),
                true,
                true
            ),
            playbackSessionArtifacts(
                StreamingProtocol.HLS,
                "/api/v1/playback/sessions/" + sessionId + "/master.m3u8",
                null,
                "/api/v1/playback/sessions/" + sessionId + "/master.m3u8",
                null
            ),
            null,
            null,
            null,
            "2026-04-09T12:00:00Z",
            playbackSessionLifecycle(
                PlaybackLifecyclePhase.STOPPED,
                "2026-04-09T12:00:00Z",
                null,
                "2026-04-09T12:00:03Z",
                100.0,
                false,
                PlaybackLifecycleEndReason.CLIENT_REQUESTED
            )
        );
    }

    private static JsonNode json(Response response) {
        try {
            return JSON.readTree(bodyAsText(response));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse response JSON", exception);
        }
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

    private static RuntimeException nyxException(ErrorCode errorCode, String message) {
        return PlaybackRoutesTest.<RuntimeException>sneakyThrow(new NyxException(errorCode, message));
    }

    private static <T> T sneakyThrow(Throwable throwable) {
        PlaybackRoutesTest.<RuntimeException>throwUnchecked(throwable);
        throw new AssertionError("Unreachable");
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private static void deleteTree(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to delete " + path, exception);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete " + root, exception);
        }
    }

    private record MediaObjectSupport(
        MediaObjectService mediaObjectService,
        MediaObjectResolver mediaObjectResolver,
        UserMediaStateService userMediaStateService
    ) {
    }

    private record CloseRequest(String sessionId, String owner) {
    }

    private static class StubPlaybackSessionService implements PlaybackSessionService {
        private final PlaybackSession session;
        private final PlaybackSession reportResponse;
        private final java.util.function.Function<MediaSessionPlaybackReport, PlaybackSession> reportResponseProvider;
        private final String hlsManifest;
        private final Path directContentPath;
        private final List<PlaybackRequest> openRequests = new ArrayList<>();
        private final List<MediaSessionPlaybackReport> reportRequests = new ArrayList<>();
        private final List<CloseRequest> closeRequests = new ArrayList<>();

        private StubPlaybackSessionService(PlaybackSession session) {
            this(session, null);
        }

        private StubPlaybackSessionService(PlaybackSession session, String hlsManifest) {
            this(session, session, null, hlsManifest, null);
        }

        private StubPlaybackSessionService(
            PlaybackSession session,
            PlaybackSession reportResponse,
            java.util.function.Function<MediaSessionPlaybackReport, PlaybackSession> reportResponseProvider
        ) {
            this(session, reportResponse, reportResponseProvider, null, null);
        }

        private StubPlaybackSessionService(
            PlaybackSession session,
            PlaybackSession reportResponse,
            java.util.function.Function<MediaSessionPlaybackReport, PlaybackSession> reportResponseProvider,
            String hlsManifest,
            Path directContentPath
        ) {
            this.session = session;
            this.reportResponse = reportResponse;
            this.reportResponseProvider = reportResponseProvider;
            this.hlsManifest = hlsManifest;
            this.directContentPath = directContentPath;
        }

        @Override
        public PlaybackSession openSession(PlaybackRequest request, String owner) {
            openRequests.add(request);
            return session;
        }

        @Override
        public PlaybackSession getSession(String sessionId, String owner) {
            return session.sessionId().equals(sessionId) ? session : null;
        }

        @Override
        public PlaybackSession reportPlayback(
            String sessionId,
            MediaSessionPlaybackReport report,
            String owner
        ) {
            reportRequests.add(report);
            return reportResponseProvider != null ? reportResponseProvider.apply(report) : reportResponse;
        }

        @Override
        public String getSessionJobId(String sessionId, String owner) {
            return null;
        }

        @Override
        public void closeSession(String sessionId, String owner) {
            closeRequests.add(new CloseRequest(sessionId, owner));
        }

        @Override
        public String getHlsManifest(String sessionId, String owner) {
            return session.sessionId().equals(sessionId) ? hlsManifest : null;
        }

        @Override
        public String getDashManifest(String sessionId, String owner) {
            return null;
        }

        @Override
        public Path getDirectContentPath(String sessionId, String owner) {
            return session.sessionId().equals(sessionId) ? directContentPath : null;
        }
    }

    private static final class StubMediaSessionReportService implements MediaSessionReportService {
        private final MediaSessionReportResult result;
        private final List<Request> reportRequests = new ArrayList<>();

        private StubMediaSessionReportService(MediaSessionReportResult result) {
            this.result = result;
        }

        @Override
        public MediaSessionReportResult reportPlayback(
            String sessionId,
            MediaSessionPlaybackReport report,
            String authenticatedUserId
        ) {
            reportRequests.add(new Request(sessionId, report, authenticatedUserId));
            return result;
        }
    }

    private record Request(
        String sessionId,
        MediaSessionPlaybackReport report,
        String authenticatedUserId
    ) {
    }

    private static final class MissingAudioSessionService implements AudioSessionService {
        @Override
        public AudioSession openSession(AudioNegotiationRequest request, String owner) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public AudioSession getSession(String sessionId, String owner) {
            return null;
        }

        @Override
        public AudioNegotiationRequest getSessionRequest(String sessionId, String owner) {
            return null;
        }

        @Override
        public AudioSession reportPlayback(String sessionId, MediaSessionPlaybackReport report, String owner) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public void closeSession(String sessionId, String owner) {
        }

        @Override
        public Path getSourcePath(String sessionId, String owner) {
            return null;
        }
    }
}
