package com.nyx.playback;

import static com.nyx.playback.PlaybackApiTestSupport.bodyAsText;
import static com.nyx.playback.PlaybackApiTestSupport.status;
import static com.nyx.playback.PlaybackContractFactories.playbackDecision;
import static com.nyx.playback.PlaybackContractFactories.streamDescriptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.ServerConfig;
import com.nyx.config.TranscodeConfig;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.json.NyxJson;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackDecisionService;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackReason;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.transcode.contracts.BatchCancelResponse;
import com.nyx.transcode.contracts.BatchStatusResponse;
import com.nyx.transcode.contracts.BatchSubmitResponse;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeApplicationService;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeJobListing;
import com.nyx.transcode.contracts.TranscodeRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class PlaybackRemuxSessionRoutesTest {
    private static final ObjectMapper JSON = NyxJson.newMapper();

    @Test
    void remuxSessionFlowServesHlsManifest() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Path tempDir = Files.createTempDirectory("nyx-playback-remux-route");
            try {
                Path mediaRoot = Files.createDirectories(tempDir.resolve("movies"));
                Files.write(mediaRoot.resolve("remux.mkv"), new byte[128]);
                RouteFakeTranscodeApplicationService transcodeService = new RouteFakeTranscodeApplicationService(
                    JobStatus.COMPLETED,
                    "#EXTM3U",
                    ""
                );
                LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(
                    remuxDecisionService(),
                    transcodeService
                );

                installPlaybackTestApp(harness, tempDir, mediaRoot, sessionService);

                String sessionId;
                try (Response openResponse = harness.client().post("/api/v1/playback/sessions", request -> {
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.toString());
                    request.setBody("{\"source\":{\"path\":\"movies/remux.mkv\"}}");
                })) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(openResponse));
                    JsonNode openBody = json(openResponse);
                    sessionId = openBody.get("sessionId").asText();
                    assertEquals("REMUX", openBody.get("decision").get("mode").asText());
                    assertEquals("READY", openBody.get("lifecycle").get("phase").asText());
                    assertEquals(PlaybackMode.REMUX, transcodeService.submittedDecisions.get("job-1").mode());
                }

                try (Response manifestResponse = harness.client().get("/api/v1/playback/sessions/" + sessionId + "/master.m3u8")) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(manifestResponse));
                    assertEquals("#EXTM3U", bodyAsText(manifestResponse));
                }
            } finally {
                deleteTree(tempDir);
            }
        });
    }

    @Test
    void remuxSessionFailureIsExposedThroughPlaybackRoutes() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Path tempDir = Files.createTempDirectory("nyx-playback-remux-failure");
            try {
                Path mediaRoot = Files.createDirectories(tempDir.resolve("movies"));
                Files.write(mediaRoot.resolve("broken-remux.mkv"), new byte[128]);
                RouteFakeTranscodeApplicationService transcodeService = new RouteFakeTranscodeApplicationService(
                    JobStatus.FAILED,
                    null,
                    "remux failed"
                );
                LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(
                    remuxDecisionService(),
                    transcodeService
                );

                installPlaybackTestApp(harness, tempDir, mediaRoot, sessionService);

                String sessionId;
                try (Response openResponse = harness.client().post("/api/v1/playback/sessions", request -> {
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.toString());
                    request.setBody("{\"source\":{\"path\":\"movies/broken-remux.mkv\"}}");
                })) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(openResponse));
                    JsonNode openBody = json(openResponse);
                    sessionId = openBody.get("sessionId").asText();
                    assertEquals("FAILED", openBody.get("state").asText());
                }

                try (Response manifestResponse = harness.client().get("/api/v1/playback/sessions/" + sessionId + "/master.m3u8")) {
                    assertEquals(HttpStatusCode.Companion.getInternalServerError(), status(manifestResponse));
                    String responseBody = bodyAsText(manifestResponse);
                    assertTrue(responseBody.contains("TRANSCODE_FAILED"));
                    assertTrue(responseBody.contains("remux failed"));
                }
            } finally {
                deleteTree(tempDir);
            }
        });
    }

    @Test
    void audioTranscodeSessionFlowServesHlsManifestWithPreservedVideoDecision() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Path tempDir = Files.createTempDirectory("nyx-playback-audio-transcode-route");
            try {
                Path mediaRoot = Files.createDirectories(tempDir.resolve("movies"));
                Files.write(mediaRoot.resolve("audio-only.mkv"), new byte[128]);
                RouteFakeTranscodeApplicationService transcodeService = new RouteFakeTranscodeApplicationService(
                    JobStatus.COMPLETED,
                    "#EXTM3U",
                    ""
                );
                LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(
                    audioTranscodeDecisionService(),
                    transcodeService
                );

                installPlaybackTestApp(harness, tempDir, mediaRoot, sessionService);

                String sessionId;
                try (Response openResponse = harness.client().post("/api/v1/playback/sessions", request -> {
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.toString());
                    request.setBody("{\"source\":{\"path\":\"movies/audio-only.mkv\"}}");
                })) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(openResponse));
                    JsonNode openBody = json(openResponse);
                    sessionId = openBody.get("sessionId").asText();
                    JsonNode decisionBody = openBody.get("decision");
                    assertEquals("AUDIO_TRANSCODE", decisionBody.get("mode").asText());
                    assertEquals("true", decisionBody.get("videoPreserved").asText());
                    assertEquals("false", decisionBody.get("audioPreserved").asText());
                    assertEquals(PlaybackMode.AUDIO_TRANSCODE, transcodeService.submittedDecisions.get("job-1").mode());
                }

                try (Response manifestResponse = harness.client().get("/api/v1/playback/sessions/" + sessionId + "/master.m3u8")) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(manifestResponse));
                    assertEquals("#EXTM3U", bodyAsText(manifestResponse));
                }
            } finally {
                deleteTree(tempDir);
            }
        });
    }

    @Test
    void audioTranscodeSessionFailureIsExposedThroughPlaybackRoutes() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Path tempDir = Files.createTempDirectory("nyx-playback-audio-transcode-failure");
            try {
                Path mediaRoot = Files.createDirectories(tempDir.resolve("movies"));
                Files.write(mediaRoot.resolve("broken-audio-only.mkv"), new byte[128]);
                RouteFakeTranscodeApplicationService transcodeService = new RouteFakeTranscodeApplicationService(
                    JobStatus.FAILED,
                    null,
                    "audio transcode failed"
                );
                LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(
                    audioTranscodeDecisionService(),
                    transcodeService
                );

                installPlaybackTestApp(harness, tempDir, mediaRoot, sessionService);

                String sessionId;
                try (Response openResponse = harness.client().post("/api/v1/playback/sessions", request -> {
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.toString());
                    request.setBody("{\"source\":{\"path\":\"movies/broken-audio-only.mkv\"}}");
                })) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(openResponse));
                    JsonNode openBody = json(openResponse);
                    sessionId = openBody.get("sessionId").asText();
                    assertEquals("FAILED", openBody.get("state").asText());
                    assertEquals("AUDIO_TRANSCODE", openBody.get("decision").get("mode").asText());
                }

                try (Response manifestResponse = harness.client().get("/api/v1/playback/sessions/" + sessionId + "/master.m3u8")) {
                    assertEquals(HttpStatusCode.Companion.getInternalServerError(), status(manifestResponse));
                    String responseBody = bodyAsText(manifestResponse);
                    assertTrue(responseBody.contains("TRANSCODE_FAILED"));
                    assertTrue(responseBody.contains("audio transcode failed"));
                }
            } finally {
                deleteTree(tempDir);
            }
        });
    }

    @Test
    void closingPlaybackSessionKeepsStoppedLifecycleVisibleAndBlocksManifestAccess() throws Exception {
        PlaybackApiTestSupport.testApplication(harness -> {
            Path tempDir = Files.createTempDirectory("nyx-playback-session-stop");
            try {
                Path mediaRoot = Files.createDirectories(tempDir.resolve("movies"));
                Files.write(mediaRoot.resolve("stop-remux.mkv"), new byte[128]);
                RouteFakeTranscodeApplicationService transcodeService = new RouteFakeTranscodeApplicationService(
                    JobStatus.COMPLETED,
                    "#EXTM3U",
                    ""
                );
                LocalPlaybackSessionService sessionService = new LocalPlaybackSessionService(
                    remuxDecisionService(),
                    transcodeService
                );

                installPlaybackTestApp(harness, tempDir, mediaRoot, sessionService);

                String sessionId;
                try (Response openResponse = harness.client().post("/api/v1/playback/sessions", request -> {
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.toString());
                    request.setBody("{\"source\":{\"path\":\"movies/stop-remux.mkv\"}}");
                })) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(openResponse));
                    sessionId = json(openResponse).get("sessionId").asText();
                }

                try (Response closeResponse = harness.client().delete("/api/v1/playback/sessions/" + sessionId)) {
                    assertEquals(HttpStatusCode.Companion.getNoContent(), status(closeResponse));
                }

                try (Response sessionResponse = harness.client().get("/api/v1/playback/sessions/" + sessionId)) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(sessionResponse));
                    JsonNode sessionBody = json(sessionResponse);
                    assertEquals("CLOSED", sessionBody.get("state").asText());
                    assertEquals("STOPPED", sessionBody.get("lifecycle").get("phase").asText());
                    assertEquals("CLIENT_REQUESTED", sessionBody.get("lifecycle").get("endReason").asText());
                }

                try (Response manifestResponse = harness.client().get("/api/v1/playback/sessions/" + sessionId + "/master.m3u8")) {
                    assertEquals(HttpStatusCode.Companion.getNotFound(), status(manifestResponse));
                }
            } finally {
                deleteTree(tempDir);
            }
        });
    }

    private static PlaybackDecisionService remuxDecisionService() {
        return request -> playbackDecision(
            PlaybackMode.REMUX,
            streamDescriptor(StreamingProtocol.HLS, "fmp4", true),
            Set.of(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED),
            true,
            true
        );
    }

    private static PlaybackDecisionService audioTranscodeDecisionService() {
        return request -> playbackDecision(
            PlaybackMode.AUDIO_TRANSCODE,
            streamDescriptor(StreamingProtocol.HLS, "fmp4", true),
            Set.of(PlaybackReason.AUDIO_CODEC_UNSUPPORTED),
            true,
            false
        );
    }

    private static void installPlaybackTestApp(
        PlaybackApiTestSupport.ApplicationHarness harness,
        Path tempDir,
        Path mediaRoot,
        LocalPlaybackSessionService sessionService
    ) {
        VirtualPathResolver resolver = new VirtualPathResolver(List.of(new MediaRootConfig(mediaRoot, "local", "movies")));
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        ServerConfig serverConfig = new ServerConfig(
            "0.0.0.0",
            8080,
            List.of(),
            List.of(new MediaRootConfig(mediaRoot, "local", "movies")),
            new FfmpegConfig("ffmpeg", "ffprobe", "6.0", 2),
            new TranscodeConfig("both", 10, 6),
            new DatabaseConfig(tempDir.resolve("db"))
        );
        PlaybackRoutes.playbackRoutes(
            harness.route(),
            sessionService,
            new LocalPlaybackDeliveryService(sessionService),
            pathSecurity,
            resolver,
            serverConfig
        );
    }

    private static JsonNode json(Response response) {
        try {
            return JSON.readTree(bodyAsText(response));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse response JSON", exception);
        }
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

    private static final class RouteFakeTranscodeApplicationService implements TranscodeApplicationService {
        private final JobStatus jobStatus;
        private final String manifestM3u8;
        private final String logs;
        private final LinkedHashMap<String, PlaybackDecision> submittedDecisions = new LinkedHashMap<>();
        private final LinkedHashMap<String, TranscodeJob> jobs = new LinkedHashMap<>();
        private Consumer<JobEvent> onJobEvent;

        private RouteFakeTranscodeApplicationService(JobStatus jobStatus, String manifestM3u8, String logs) {
            this.jobStatus = jobStatus;
            this.manifestM3u8 = manifestM3u8;
            this.logs = logs;
        }

        @Override
        public boolean getCircuitBreakerOpen() {
            return false;
        }

        @Override
        public Consumer<JobEvent> getOnJobEvent() {
            return onJobEvent;
        }

        @Override
        public void setOnJobEvent(Consumer<? super JobEvent> onJobEvent) {
            this.onJobEvent = onJobEvent == null ? null : onJobEvent::accept;
        }

        @Override
        public Flow.Publisher<JobEvent> eventFlow(String jobId) {
            return null;
        }

        @Override
        public TranscodeJob submit(TranscodeRequest request, String batchId, String owner) {
            throw new UnsupportedOperationException("Playback route tests only submit PlaybackRequest jobs");
        }

        @Override
        public TranscodeJob submit(PlaybackRequest request, PlaybackDecision decision, String batchId, String owner) {
            TranscodeJob job = new TranscodeJob(
                "job-" + (jobs.size() + 1),
                jobStatus,
                request.source().path(),
                request.transcode().profileHint() == null ? "h264_fast" : request.transcode().profileHint(),
                StreamRepresentation.HLS_FMP4
            );
            jobs.put(job.getId(), job);
            submittedDecisions.put(job.getId(), decision);
            return job;
        }

        @Override
        public BatchSubmitResponse submitBatch(List<TranscodeRequest> requests, String owner) {
            throw new UnsupportedOperationException("Not used in playback route tests");
        }

        @Override
        public void cancel(String jobId, String owner) {
        }

        @Override
        public BatchCancelResponse cancelBatch(String batchId, String owner) {
            return null;
        }

        @Override
        public BatchStatusResponse getBatchStatus(String batchId, String owner) {
            return null;
        }

        @Override
        public TranscodeJob getJob(String jobId) {
            return jobs.get(jobId);
        }

        @Override
        public TranscodeJobListing listJobs(int page, int limit, String owner) {
            return new TranscodeJobListing(List.copyOf(jobs.values()), jobs.size(), page, limit);
        }

        @Override
        public TranscodeJobListing listJobsFiltered(
            JobStatus status,
            Integer sinceMinutes,
            int page,
            int limit,
            String owner
        ) {
            return new TranscodeJobListing(List.copyOf(jobs.values()), jobs.size(), page, limit);
        }

        @Override
        public String getLogs(String jobId) {
            return logs;
        }

        @Override
        public String getManifestMpd(String jobId) {
            return null;
        }

        @Override
        public String getManifestM3u8(String jobId) {
            return manifestM3u8;
        }

        @Override
        public String getSubtitlePlaylist(String jobId, int trackIndex) {
            return null;
        }

        @Override
        public String getHlsMediaPlaylist(String jobId, String representationId) {
            return null;
        }

        @Override
        public Path getSegmentOutputDir(String jobId) {
            return null;
        }
    }
}
